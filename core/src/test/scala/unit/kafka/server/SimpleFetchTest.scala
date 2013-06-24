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
package kafka.server

import kafka.cluster.{Partition, Replica}
import kafka.log.Log
import kafka.message.{ByteBufferMessageSet, Message}
import kafka.network.{BoundedByteBufferSend, RequestChannel}
import kafka.utils.{Time, TestUtils, MockTime}
import org.easymock.EasyMock
import org.I0Itec.zkclient.ZkClient
import org.scalatest.junit.JUnit3Suite
import kafka.api._
import scala.Some
import org.junit.Assert._
import kafka.common.TopicAndPartition


class SimpleFetchTest extends JUnit3Suite {

  val configs = TestUtils.createBrokerConfigs(2).map(new KafkaConfig(_) {
    override val replicaLagTimeMaxMs = 100L
    override val replicaLagMaxMessages = 10L
  })
  val topic = "foo"
  val partitionId = 0

  /**
   * The scenario for this test is that there is one topic, "test-topic", one broker "0" that has
   * one  partition with one follower replica on broker "1".  The leader replica on "0"
   * has HW of "5" and LEO of "20".  The follower on broker "1" has a local replica
   * with a HW matching the leader's ("5") and LEO of "15", meaning it's not in-sync
   * but is still in ISR (hasn't yet expired from ISR).
   *
   * When a normal consumer fetches data, it only should only see data upto the HW of the leader,
   * in this case up an offset of "5".
   */
  def testNonReplicaSeesHwWhenFetching() {
    /* setup */
    val time = new MockTime
    val leo = 20
    val hw = 5
    val fetchSize = 100
    val messages = new Message("test-message".getBytes())

    val zkClient = EasyMock.createMock(classOf[ZkClient])
    EasyMock.replay(zkClient)

    val log = EasyMock.createMock(classOf[kafka.log.Log])
    EasyMock.expect(log.logEndOffset).andReturn(leo).anyTimes()
    EasyMock.expect(log)
    EasyMock.expect(log.read(0, fetchSize, Some(hw))).andReturn(new ByteBufferMessageSet(messages))
    EasyMock.replay(log)

    val logManager = EasyMock.createMock(classOf[kafka.log.LogManager])
    EasyMock.expect(logManager.getLog(TopicAndPartition(topic, partitionId))).andReturn(Some(log)).anyTimes()
    EasyMock.replay(logManager)

    val replicaManager = EasyMock.createMock(classOf[kafka.server.ReplicaManager])
    EasyMock.expect(replicaManager.config).andReturn(configs.head)
    EasyMock.expect(replicaManager.logManager).andReturn(logManager)
    EasyMock.expect(replicaManager.replicaFetcherManager).andReturn(EasyMock.createMock(classOf[ReplicaFetcherManager]))
    EasyMock.expect(replicaManager.zkClient).andReturn(zkClient)
    EasyMock.replay(replicaManager)

    val partition = getPartitionWithAllReplicasInISR(topic, partitionId, time, configs.head.brokerId, log, hw, replicaManager)
    partition.getReplica(configs(1).brokerId).get.logEndOffset = leo - 5L

    EasyMock.reset(replicaManager)
    EasyMock.expect(replicaManager.config).andReturn(configs.head).anyTimes()
    EasyMock.expect(replicaManager.getLeaderReplicaIfLocal(topic, partitionId)).andReturn(partition.leaderReplicaIfLocal().get).anyTimes()
    EasyMock.replay(replicaManager)

    // start a request channel with 2 processors and a queue size of 5 (this is more or less arbitrary)
    // don't provide replica or leader callbacks since they will not be tested here
    val requestChannel = new RequestChannel(2, 5)
    val apis = new KafkaApis(requestChannel, replicaManager, zkClient, configs.head.brokerId, configs.head)

    // This request (from a follower) wants to read up to 2*HW but should only get back up to HW bytes into the log
    val goodFetch = new FetchRequestBuilder()
          .replicaId(Request.OrdinaryConsumerId)
          .addFetch(topic, partitionId, 0, fetchSize)
          .build()
    val goodFetchBB = TestUtils.createRequestByteBuffer(goodFetch)

    // send the request
    apis.handleFetchRequest(new RequestChannel.Request(processor=1, requestKey=5, buffer=goodFetchBB, startTimeMs=1))

    // make sure the log only reads bytes between 0->HW (5)
    EasyMock.verify(log)
  }

  /**
   * The scenario for this test is that there is one topic, "test-topic", on broker "0" that has
   * one  partition with one follower replica on broker "1".  The leader replica on "0"
   * has HW of "5" and LEO of "20".  The follower on broker "1" has a local replica
   * with a HW matching the leader's ("5") and LEO of "15", meaning it's not in-sync
   * but is still in ISR (hasn't yet expired from ISR).
   *
   * When the follower from broker "1" fetches data, it should see data upto the log end offset ("20")
   */
  def testReplicaSeesLeoWhenFetching() {
    /* setup */
    val time = new MockTime
    val leo = 20
    val hw = 5

    val messages = new Message("test-message".getBytes())

    val followerReplicaId = configs(1).brokerId
    val followerLEO = 15

    val zkClient = EasyMock.createMock(classOf[ZkClient])
    EasyMock.replay(zkClient)

    val log = EasyMock.createMock(classOf[kafka.log.Log])
    EasyMock.expect(log.logEndOffset).andReturn(leo).anyTimes()
    EasyMock.expect(log.read(followerLEO, Integer.MAX_VALUE, None)).andReturn(new ByteBufferMessageSet(messages))
    EasyMock.replay(log)

    val logManager = EasyMock.createMock(classOf[kafka.log.LogManager])
    EasyMock.expect(logManager.getLog(TopicAndPartition(topic, 0))).andReturn(Some(log)).anyTimes()
    EasyMock.replay(logManager)

    val replicaManager = EasyMock.createMock(classOf[kafka.server.ReplicaManager])
    EasyMock.expect(replicaManager.config).andReturn(configs.head)
    EasyMock.expect(replicaManager.logManager).andReturn(logManager)
    EasyMock.expect(replicaManager.replicaFetcherManager).andReturn(EasyMock.createMock(classOf[ReplicaFetcherManager]))
    EasyMock.expect(replicaManager.zkClient).andReturn(zkClient)
    EasyMock.replay(replicaManager)

    val partition = getPartitionWithAllReplicasInISR(topic, partitionId, time, configs.head.brokerId, log, hw, replicaManager)
    partition.getReplica(followerReplicaId).get.logEndOffset = followerLEO.asInstanceOf[Long]

    EasyMock.reset(replicaManager)
    EasyMock.expect(replicaManager.config).andReturn(configs.head).anyTimes()
    EasyMock.expect(replicaManager.recordFollowerPosition(topic, partitionId, followerReplicaId, followerLEO))
    EasyMock.expect(replicaManager.getReplica(topic, partitionId, followerReplicaId)).andReturn(partition.inSyncReplicas.find(_.brokerId == configs(1).brokerId))
    EasyMock.expect(replicaManager.getLeaderReplicaIfLocal(topic, partitionId)).andReturn(partition.leaderReplicaIfLocal().get).anyTimes()
    EasyMock.replay(replicaManager)

    val requestChannel = new RequestChannel(2, 5)
    val apis = new KafkaApis(requestChannel, replicaManager, zkClient, configs.head.brokerId, configs.head)

    /**
     * This fetch, coming from a replica, requests all data at offset "15".  Because the request is coming
     * from a follower, the leader should oblige and read beyond the HW.
     */
    val bigFetch = new FetchRequestBuilder()
      .replicaId(followerReplicaId)
      .addFetch(topic, partitionId, followerLEO, Integer.MAX_VALUE)
      .build()

    val fetchRequestBB = TestUtils.createRequestByteBuffer(bigFetch)

    // send the request
    apis.handleFetchRequest(new RequestChannel.Request(processor=0, requestKey=5, buffer=fetchRequestBB, startTimeMs=1))

    /**
     * Make sure the log satisfies the fetch from a follower by reading data beyond the HW, mainly all bytes after
     * an offset of 15
     */
    EasyMock.verify(log)
  }

  private def getPartitionWithAllReplicasInISR(topic: String, partitionId: Int, time: Time, leaderId: Int,
                                               localLog: Log, leaderHW: Long, replicaManager: ReplicaManager): Partition = {
    val partition = new Partition(topic, partitionId, 2, time, replicaManager)
    val leaderReplica = new Replica(leaderId, partition, time, 0, Some(localLog))

    val allReplicas = getFollowerReplicas(partition, leaderId, time) :+ leaderReplica
    allReplicas.foreach(partition.addReplicaIfNotExists(_))
    // set in sync replicas for this partition to all the assigned replicas
    partition.inSyncReplicas = allReplicas.toSet
    // set the leader and its hw and the hw update time
    partition.leaderReplicaIdOpt = Some(leaderId)
    leaderReplica.highWatermark = leaderHW
    partition
  }

  private def getFollowerReplicas(partition: Partition, leaderId: Int, time: Time): Seq[Replica] = {
    configs.filter(_.brokerId != leaderId).map { config =>
      new Replica(config.brokerId, partition, time)
    }
  }

}
