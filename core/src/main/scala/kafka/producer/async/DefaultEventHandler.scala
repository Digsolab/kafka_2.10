/**
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

package kafka.producer.async

import kafka.common._
import kafka.message.{NoCompressionCodec, Message, ByteBufferMessageSet}
import kafka.producer._
import kafka.serializer.Encoder
import kafka.utils.{Utils, Logging, SystemTime}
import scala.collection.{Seq, Map}
import scala.collection.mutable.{ArrayBuffer, HashMap, Set}
import java.util.concurrent.atomic._
import kafka.api.{TopicMetadata, ProducerRequest}

class DefaultEventHandler[K,V](config: ProducerConfig,
                               private val partitioner: Partitioner[K],
                               private val encoder: Encoder[V],
                               private val keyEncoder: Encoder[K],
                               private val producerPool: ProducerPool,
                               private val topicPartitionInfos: HashMap[String, TopicMetadata] = new HashMap[String, TopicMetadata])
  extends EventHandler[K,V] with Logging {
  val isSync = ("sync" == config.producerType)

  val partitionCounter = new AtomicInteger(0)
  val correlationId = new AtomicInteger(0)
  val brokerPartitionInfo = new BrokerPartitionInfo(config, producerPool, topicPartitionInfos)

  private val lock = new Object()

  private val topicMetadataRefreshInterval = config.topicMetadataRefreshIntervalMs
  private var lastTopicMetadataRefreshTime = 0L
  private val topicMetadataToRefresh = Set.empty[String]

  private val producerStats = ProducerStatsRegistry.getProducerStats(config.clientId)
  private val producerTopicStats = ProducerTopicStatsRegistry.getProducerTopicStats(config.clientId)

  def handle(events: Seq[KeyedMessage[K,V]]) {
    lock synchronized {
      val serializedData = serialize(events)
      serializedData.foreach{
        keyed =>
          val dataSize = keyed.message.payloadSize
          producerTopicStats.getProducerTopicStats(keyed.topic).byteRate.mark(dataSize)
          producerTopicStats.getProducerAllTopicsStats.byteRate.mark(dataSize)
      }
      var outstandingProduceRequests = serializedData
      var remainingRetries = config.messageSendMaxRetries + 1
      val correlationIdStart = correlationId.get()
      while (remainingRetries > 0 && outstandingProduceRequests.size > 0) {
        topicMetadataToRefresh ++= outstandingProduceRequests.map(_.topic)
        if (topicMetadataRefreshInterval >= 0 &&
            SystemTime.milliseconds - lastTopicMetadataRefreshTime > topicMetadataRefreshInterval) {
          Utils.swallowError(brokerPartitionInfo.updateInfo(topicMetadataToRefresh.toSet, correlationId.getAndIncrement))
          topicMetadataToRefresh.clear
          lastTopicMetadataRefreshTime = SystemTime.milliseconds
        }
        outstandingProduceRequests = dispatchSerializedData(outstandingProduceRequests)
        if (outstandingProduceRequests.size > 0)  {
          // back off and update the topic metadata cache before attempting another send operation
          Thread.sleep(config.retryBackoffMs)
          // get topics of the outstanding produce requests and refresh metadata for those
          Utils.swallowError(brokerPartitionInfo.updateInfo(outstandingProduceRequests.map(_.topic).toSet, correlationId.getAndIncrement))
          remainingRetries -= 1
          producerStats.resendRate.mark()
        }
      }
      if(outstandingProduceRequests.size > 0) {
        producerStats.failedSendRate.mark()
        val correlationIdEnd = correlationId.get()
        error("Failed to send requests for topics %s with correlation ids in [%d,%d]"
          .format(outstandingProduceRequests.map(_.topic).toSet.mkString(","),
          correlationIdStart, correlationIdEnd-1))
        throw new FailedToSendMessageException("Failed to send messages after " + config.messageSendMaxRetries + " tries.", null)
      }
    }
  }

  private def dispatchSerializedData(messages: Seq[KeyedMessage[K,Message]]): Seq[KeyedMessage[K, Message]] = {
    val partitionedDataOpt = partitionAndCollate(messages)
    partitionedDataOpt match {
      case Some(partitionedData) =>
        val failedProduceRequests = new ArrayBuffer[KeyedMessage[K,Message]]
        try {
          for ((brokerid, messagesPerBrokerMap) <- partitionedData) {
            if (logger.isTraceEnabled)
              messagesPerBrokerMap.foreach(partitionAndEvent =>
                trace("Handling event for Topic: %s, Broker: %d, Partitions: %s".format(partitionAndEvent._1, brokerid, partitionAndEvent._2)))
            val messageSetPerBroker = groupMessagesToSet(messagesPerBrokerMap)

            val failedTopicPartitions = send(brokerid, messageSetPerBroker)
            failedTopicPartitions.foreach(topicPartition => {
              messagesPerBrokerMap.get(topicPartition) match {
                case Some(data) => failedProduceRequests.appendAll(data)
                case None => // nothing
              }
            })
          }
        } catch {
          case t: Throwable => error("Failed to send messages", t)
        }
        failedProduceRequests
      case None => // all produce requests failed
        messages
    }
  }

  def serialize(events: Seq[KeyedMessage[K,V]]): Seq[KeyedMessage[K,Message]] = {
    val serializedMessages = new ArrayBuffer[KeyedMessage[K,Message]](events.size)
    events.map{e =>
      try {
        if(e.hasKey)
          serializedMessages += KeyedMessage[K,Message](topic = e.topic, key = e.key, message = new Message(key = keyEncoder.toBytes(e.key), bytes = encoder.toBytes(e.message)))
        else
          serializedMessages += KeyedMessage[K,Message](topic = e.topic, key = null.asInstanceOf[K], message = new Message(bytes = encoder.toBytes(e.message)))
      } catch {
        case t =>
          producerStats.serializationErrorRate.mark()
          if (isSync) {
            throw t
          } else {
            // currently, if in async mode, we just log the serialization error. We need to revisit
            // this when doing kafka-496
            error("Error serializing message ", t)
          }
      }
    }
    serializedMessages
  }

  def partitionAndCollate(messages: Seq[KeyedMessage[K,Message]]): Option[Map[Int, collection.mutable.Map[TopicAndPartition, Seq[KeyedMessage[K,Message]]]]] = {
    val ret = new HashMap[Int, collection.mutable.Map[TopicAndPartition, Seq[KeyedMessage[K,Message]]]]
    try {
      for (message <- messages) {
        val topicPartitionsList = getPartitionListForTopic(message)
        val partitionIndex = getPartition(message.key, topicPartitionsList)
        val brokerPartition = topicPartitionsList(partitionIndex)

        // postpone the failure until the send operation, so that requests for other brokers are handled correctly
        val leaderBrokerId = brokerPartition.leaderBrokerIdOpt.getOrElse(-1)

        var dataPerBroker: HashMap[TopicAndPartition, Seq[KeyedMessage[K,Message]]] = null
        ret.get(leaderBrokerId) match {
          case Some(element) =>
            dataPerBroker = element.asInstanceOf[HashMap[TopicAndPartition, Seq[KeyedMessage[K,Message]]]]
          case None =>
            dataPerBroker = new HashMap[TopicAndPartition, Seq[KeyedMessage[K,Message]]]
            ret.put(leaderBrokerId, dataPerBroker)
        }

        val topicAndPartition = TopicAndPartition(message.topic, brokerPartition.partitionId)
        var dataPerTopicPartition: ArrayBuffer[KeyedMessage[K,Message]] = null
        dataPerBroker.get(topicAndPartition) match {
          case Some(element) =>
            dataPerTopicPartition = element.asInstanceOf[ArrayBuffer[KeyedMessage[K,Message]]]
          case None =>
            dataPerTopicPartition = new ArrayBuffer[KeyedMessage[K,Message]]
            dataPerBroker.put(topicAndPartition, dataPerTopicPartition)
        }
        dataPerTopicPartition.append(message)
      }
      Some(ret)
    }catch {    // Swallow recoverable exceptions and return None so that they can be retried.
      case ute: UnknownTopicException => warn("Failed to collate messages by topic,partition due to", ute); None
      case lnae: LeaderNotAvailableException => warn("Failed to collate messages by topic,partition due to", lnae); None
      case oe => error("Failed to collate messages by topic, partition due to", oe); throw oe
    }
  }

  private def getPartitionListForTopic(m: KeyedMessage[K,Message]): Seq[PartitionAndLeader] = {
    val topicPartitionsList = brokerPartitionInfo.getBrokerPartitionInfo(m.topic, correlationId.getAndIncrement)
    debug("Broker partitions registered for topic: %s are %s"
      .format(m.topic, topicPartitionsList.map(p => p.partitionId).mkString(",")))
    val totalNumPartitions = topicPartitionsList.length
    if(totalNumPartitions == 0)
      throw new NoBrokersForPartitionException("Partition key = " + m.key)
    topicPartitionsList
  }

  /**
   * Retrieves the partition id and throws an UnknownTopicOrPartitionException if
   * the value of partition is not between 0 and numPartitions-1
   * @param key the partition key
   * @param topicPartitionList the list of available partitions
   * @return the partition id
   */
  private def getPartition(key: K, topicPartitionList: Seq[PartitionAndLeader]): Int = {
    val numPartitions = topicPartitionList.size
    if(numPartitions <= 0)
      throw new UnknownTopicOrPartitionException("Invalid number of partitions: " + numPartitions +
        "\n Valid values are > 0")
    val partition =
      if(key == null) {
        // If the key is null, we don't really need a partitioner so we just send to the next
        // available partition
        val availablePartitions = topicPartitionList.filter(_.leaderBrokerIdOpt.isDefined)
        if (availablePartitions.isEmpty)
          throw new LeaderNotAvailableException("No leader for any partition")
        val index = Utils.abs(partitionCounter.getAndIncrement()) % availablePartitions.size
        availablePartitions(index).partitionId
      } else
        partitioner.partition(key, numPartitions)
    if(partition < 0 || partition >= numPartitions)
      throw new UnknownTopicOrPartitionException("Invalid partition id : " + partition +
        "\n Valid values are in the range inclusive [0, " + (numPartitions-1) + "]")
    partition
  }

  /**
   * Constructs and sends the produce request based on a map from (topic, partition) -> messages
   *
   * @param brokerId the broker that will receive the request
   * @param messagesPerTopic the messages as a map from (topic, partition) -> messages
   * @return the set (topic, partitions) messages which incurred an error sending or processing
   */
  private def send(brokerId: Int, messagesPerTopic: collection.mutable.Map[TopicAndPartition, ByteBufferMessageSet]) = {
    if(brokerId < 0) {
      warn("Failed to send data since partitions %s don't have a leader".format(messagesPerTopic.map(_._1).mkString(",")))
      messagesPerTopic.keys.toSeq
    } else if(messagesPerTopic.size > 0) {
      val currentCorrelationId = correlationId.getAndIncrement
      val producerRequest = new ProducerRequest(currentCorrelationId, config.clientId, config.requestRequiredAcks,
        config.requestTimeoutMs, messagesPerTopic)
      var failedTopicPartitions = Seq.empty[TopicAndPartition]
      try {
        val syncProducer = producerPool.getProducer(brokerId)
        debug("Producer sending messages with correlation id %d for topics %s to broker %d on %s:%d"
          .format(currentCorrelationId, messagesPerTopic.keySet.mkString(","), brokerId, syncProducer.config.host, syncProducer.config.port))
        val response = syncProducer.send(producerRequest)
        debug("Producer sent messages with correlation id %d for topics %s to broker %d on %s:%d"
          .format(currentCorrelationId, messagesPerTopic.keySet.mkString(","), brokerId, syncProducer.config.host, syncProducer.config.port))
        if(response != null) {
          if (response.status.size != producerRequest.data.size)
            throw new KafkaException("Incomplete response (%s) for producer request (%s)".format(response, producerRequest))
          if (logger.isTraceEnabled) {
            val successfullySentData = response.status.filter(_._2.error == ErrorMapping.NoError)
            successfullySentData.foreach(m => messagesPerTopic(m._1).foreach(message =>
              trace("Successfully sent message: %s".format(if(message.message.isNull) null else Utils.readString(message.message.payload)))))
          }
          failedTopicPartitions = response.status.filter(_._2.error != ErrorMapping.NoError).toSeq
            .map(partitionStatus => partitionStatus._1)
          if(failedTopicPartitions.size > 0)
            error("Produce request with correlation id %d failed due to response %s. List of failed topic partitions is %s"
              .format(currentCorrelationId, response.toString, failedTopicPartitions.mkString(",")))
          failedTopicPartitions
        } else {
          Seq.empty[TopicAndPartition]
        }
      } catch {
        case t: Throwable =>
          warn("Failed to send producer request with correlation id %d to broker %d with data for partitions %s"
            .format(currentCorrelationId, brokerId, messagesPerTopic.map(_._1).mkString(",")), t)
          messagesPerTopic.keys.toSeq
      }
    } else {
      List.empty
    }
  }

  private def groupMessagesToSet(messagesPerTopicAndPartition: collection.mutable.Map[TopicAndPartition, Seq[KeyedMessage[K,Message]]]) = {
    /** enforce the compressed.topics config here.
      *  If the compression codec is anything other than NoCompressionCodec,
      *    Enable compression only for specified topics if any
      *    If the list of compressed topics is empty, then enable the specified compression codec for all topics
      *  If the compression codec is NoCompressionCodec, compression is disabled for all topics
      */

    val messagesPerTopicPartition = messagesPerTopicAndPartition.map { case (topicAndPartition, messages) =>
      val rawMessages = messages.map(_.message)
      ( topicAndPartition,
        config.compressionCodec match {
          case NoCompressionCodec =>
            debug("Sending %d messages with no compression to %s".format(messages.size, topicAndPartition))
            new ByteBufferMessageSet(NoCompressionCodec, rawMessages: _*)
          case _ =>
            config.compressedTopics.size match {
              case 0 =>
                debug("Sending %d messages with compression codec %d to %s"
                  .format(messages.size, config.compressionCodec.codec, topicAndPartition))
                new ByteBufferMessageSet(config.compressionCodec, rawMessages: _*)
              case _ =>
                if(config.compressedTopics.contains(topicAndPartition.topic)) {
                  debug("Sending %d messages with compression codec %d to %s"
                    .format(messages.size, config.compressionCodec.codec, topicAndPartition))
                  new ByteBufferMessageSet(config.compressionCodec, rawMessages: _*)
                }
                else {
                  debug("Sending %d messages to %s with no compression as it is not in compressed.topics - %s"
                    .format(messages.size, topicAndPartition, config.compressedTopics.toString))
                  new ByteBufferMessageSet(NoCompressionCodec, rawMessages: _*)
                }
            }
        }
        )
    }
    messagesPerTopicPartition
  }

  def close() {
    if (producerPool != null)
      producerPool.close
  }
}
