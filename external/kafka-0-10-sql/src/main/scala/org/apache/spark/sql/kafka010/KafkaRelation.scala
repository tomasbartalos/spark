/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.kafka010

import java.sql.Timestamp
import java.util.UUID

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition

import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Row, SQLContext}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String


private[kafka010] class KafkaRelation(
    override val sqlContext: SQLContext,
    strategy: ConsumerStrategy,
    sourceOptions: Map[String, String],
    specifiedKafkaParams: Map[String, String],
    failOnDataLoss: Boolean,
    startingOffsets: KafkaOffsetRangeLimit,
    endingOffsets: KafkaOffsetRangeLimit)
    extends BaseRelation with PrunedFilteredScan with Logging {
  assert(startingOffsets != LatestOffsetRangeLimit,
    "Starting offset not allowed to be set to latest offsets.")
  assert(endingOffsets != EarliestOffsetRangeLimit,
    "Ending offset not allowed to be set to earliest offsets.")

  private val pollTimeoutMs = sourceOptions.getOrElse(
    "kafkaConsumer.pollTimeoutMs",
    (sqlContext.sparkContext.conf.getTimeAsSeconds(
      "spark.network.timeout",
      "120s") * 1000L).toString
  ).toLong

  override def schema: StructType = KafkaOffsetReader.kafkaSchema

  def buildScan(requiredColumns: Array[String], filters: Array[Filter]): RDD[Row] = {
    // Each running query should use its own group id. Otherwise, the query may be only assigned
    // partial data since Kafka will assign partitions to multiple consumers having the same group
    // id. Hence, we should generate a unique id for each query.
    val uniqueGroupId = s"spark-kafka-relation-${UUID.randomUUID}"

    val kafkaOffsetReader = new KafkaOffsetReader(
      strategy,
      KafkaSourceProvider.kafkaParamsForDriver(specifiedKafkaParams),
      sourceOptions,
      driverGroupIdPrefix = s"$uniqueGroupId-driver")

    // Leverage the KafkaReader to obtain the relevant partition offsets
    val (fromPartitionOffsets, untilPartitionOffsets) = {
      try {
        (getStartingPartitionOffsets(kafkaOffsetReader, filters),
          getEndingPartitionOffsets(kafkaOffsetReader, filters))
      } finally {
        kafkaOffsetReader.close()
      }
    }

    // Obtain topicPartitions in both from and until partition offset, ignoring
    // topic partitions that were added and/or deleted between the two above calls.
    if (fromPartitionOffsets.keySet != untilPartitionOffsets.keySet) {
      implicit val topicOrdering: Ordering[TopicPartition] = Ordering.by(t => t.topic())
      val fromTopics = fromPartitionOffsets.keySet.toList.sorted.mkString(",")
      val untilTopics = untilPartitionOffsets.keySet.toList.sorted.mkString(",")
      throw new IllegalStateException("different topic partitions " +
        s"for starting offsets topics[${fromTopics}] and " +
        s"ending offsets topics[${untilTopics}]")
    }

    // Calculate offset ranges
    val offsetRanges = untilPartitionOffsets.keySet.map { tp =>
      val fromOffset = fromPartitionOffsets.getOrElse(tp,
        // This should not happen since topicPartitions contains all partitions not in
        // fromPartitionOffsets
        throw new IllegalStateException(s"$tp doesn't have a from offset"))
      var untilOffset = untilPartitionOffsets(tp)
      untilOffset = if (areOffsetsInLine(fromOffset, untilOffset)) untilOffset else fromOffset
      KafkaSourceRDDOffsetRange(tp, fromOffset, untilOffset, None)
    }.toArray

    logInfo("GetBatch generating RDD of offset range: " +
      offsetRanges.sortBy(_.topicPartition.toString).mkString(", "))

    // Create an RDD that reads from Kafka and get the (key, value) pair as byte arrays.
    val executorKafkaParams =
      KafkaSourceProvider.kafkaParamsForExecutors(specifiedKafkaParams, uniqueGroupId)
    val rdd = new KafkaSourceRDD(
      sqlContext.sparkContext, executorKafkaParams, offsetRanges,
      pollTimeoutMs, failOnDataLoss, reuseKafkaConsumer = false).map { cr =>
        val columns = requiredColumns.map{KafkaRelation.columnToValueExtractor(_)(cr)}
        InternalRow.fromSeq(columns)
      }
    val schema = StructType(requiredColumns.map{KafkaRelation.columnToSchema})
    sqlContext.internalCreateDataFrame(rdd.setName("kafka"), schema).rdd
  }

  private def areOffsetsInLine(fromOffset: Long, untilOffset: Long) = {
    untilOffset > fromOffset || untilOffset < 0 || fromOffset < 0
  }

  private def getEndingPartitionOffsets(
      kafkaReader: KafkaOffsetReader,
      filters: Array[Filter]): Map[TopicPartition, Long] = {

    val offsetsByLimit = getPartitionOffsetsByRangeLimit(kafkaReader, endingOffsets)
    getEndingPartitionOffsetsByFilter(kafkaReader, offsetsByLimit, filters)
  }

  private def getStartingPartitionOffsets(
      kafkaReader: KafkaOffsetReader,
      filters: Array[Filter]): Map[TopicPartition, Long] = {

    val offsetsByLimit = getPartitionOffsetsByRangeLimit(kafkaReader, startingOffsets)
    getStartingPartitionOffsetsByFilter(kafkaReader, offsetsByLimit, filters)
  }

  private def getPartitionOffsetsByRangeLimit(
      kafkaReader: KafkaOffsetReader,
      kafkaOffsets: KafkaOffsetRangeLimit): Map[TopicPartition, Long] = {
    def validateTopicPartitions(partitions: Set[TopicPartition],
      partitionOffsets: Map[TopicPartition, Long]): Map[TopicPartition, Long] = {
      assert(partitions == partitionOffsets.keySet,
        "If startingOffsets contains specific offsets, you must specify all TopicPartitions.\n" +
          "Use -1 for latest, -2 for earliest, if you don't care.\n" +
          s"Specified: ${partitionOffsets.keySet} Assigned: ${partitions}")
      logDebug(s"Partitions assigned to consumer: $partitions. Seeking to $partitionOffsets")
      partitionOffsets
    }
    val partitions = kafkaReader.fetchTopicPartitions()
    // Obtain TopicPartition offsets with late binding support
    kafkaOffsets match {
      case EarliestOffsetRangeLimit => partitions.map {
        case tp => tp -> KafkaOffsetRangeLimit.EARLIEST
      }.toMap
      case LatestOffsetRangeLimit => partitions.map {
        case tp => tp -> KafkaOffsetRangeLimit.LATEST
      }.toMap
      case SpecificOffsetRangeLimit(partitionOffsets) =>
        validateTopicPartitions(partitions, partitionOffsets)
    }
  }

  private val TIMESTAMP_ATTR = "timestamp"

  private def getStartingPartitionOffsetsByFilter(
       kafkaReader: KafkaOffsetReader,
       limitOffsets: Map[TopicPartition, Long],
       filters: Array[Filter]): Map[TopicPartition, Long] = {

    val timeOffsets: Map[TopicPartition, Long] = filters.flatMap {
      case op: GreaterThan if op.attribute == TIMESTAMP_ATTR =>
        val times = limitOffsets.map { case (tp, _) =>
          tp -> (op.value.asInstanceOf[Timestamp].getTime + 1)}
        kafkaReader.fetchOffsetsByTime(times)
      case op: EqualTo if op.attribute == TIMESTAMP_ATTR =>
        val times = limitOffsets.map { case (tp, _) =>
          tp -> op.value.asInstanceOf[Timestamp].getTime}
        kafkaReader.fetchOffsetsByTime(times)
      case op: GreaterThanOrEqual if op.attribute == TIMESTAMP_ATTR =>
        val times = limitOffsets.map { case (tp, _) =>
          tp -> op.value.asInstanceOf[Timestamp].getTime}
        kafkaReader.fetchOffsetsByTime(times)
      case _ => None
    }.toMap

    limitOffsets.map {case (tp, offset) =>
      tp -> math.max(offset, timeOffsets.getOrElse(tp, offset))
    }
  }

  private def getEndingPartitionOffsetsByFilter(
      kafkaReader: KafkaOffsetReader,
      limitOffsets: Map[TopicPartition, Long],
      filters: Array[Filter]): Map[TopicPartition, Long] = {

    val timeOffsets: Map[TopicPartition, Long] = filters.flatMap {
      case op: LessThan if op.attribute == TIMESTAMP_ATTR =>
        val times = limitOffsets.map { case (tp, _) =>
          tp -> (op.value.asInstanceOf[Timestamp].getTime - 1)}
        kafkaReader.fetchOffsetsByTime(times)
      case op: LessThanOrEqual if op.attribute == TIMESTAMP_ATTR =>
        val times = limitOffsets.map { case (tp, _) =>
          tp -> op.value.asInstanceOf[Timestamp].getTime}
        kafkaReader.fetchOffsetsByTime(times)
      case op: EqualTo if op.attribute == TIMESTAMP_ATTR =>
        val times = limitOffsets.map { case (tp, _) =>
          tp -> (op.value.asInstanceOf[Timestamp].getTime + 1)}
        kafkaReader.fetchOffsetsByTime(times)
      case _ => None
    }.toMap

    limitOffsets.map {case (tp, offset) =>
      var newOffset = timeOffsets.getOrElse(tp, offset)
      if (isLimitSpecified(offset)) {
        newOffset = Math.min(offset, newOffset)
      }
      tp -> newOffset
    }
  }

  private def isLimitSpecified(offset: Long): Boolean = {
    offset >= 0
  }

  override def toString: String =
    s"KafkaRelation(strategy=$strategy, start=$startingOffsets, end=$endingOffsets)"
}

object KafkaRelation {
  private val columnToValueExtractor = Map[String, ConsumerRecord[Array[Byte], Array[Byte]] => Any](
    "key" -> (cr => cr.key),
    "value" -> (cr => cr.value),
    "topic" -> (cr => UTF8String.fromString(cr.topic)),
    "partition" -> (cr => cr.partition),
    "offset" -> (cr => cr.offset),
    "timestamp" -> (cr => DateTimeUtils.fromJavaTimestamp(new java.sql.Timestamp(cr.timestamp))),
    "timestampType" -> (cr => cr.timestampType.id)
  )

  private val columnToSchema = Map[String, StructField](
    "key" -> StructField("key", BinaryType),
    "value" -> StructField("value", BinaryType),
    "topic" -> StructField("topic", StringType),
    "partition" -> StructField("partition", IntegerType),
    "offset" -> StructField("offset", LongType),
    "timestamp" -> StructField("timestamp", TimestampType),
    "timestampType" -> StructField("timestampType", IntegerType)
  )
}
