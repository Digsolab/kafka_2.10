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

package kafka.log

import scala.collection._
import scala.math
import java.nio._
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic._
import java.util.Date
import java.io.File
import kafka.common._
import kafka.message._
import kafka.server.OffsetCheckpoint
import kafka.utils._

/**
 * The cleaner is responsible for removing obsolete records from logs which have the dedupe retention strategy.
 * A message with key K and offset O is obsolete if there exists a message with key K and offset O' such that O < O'.
 * 
 * Each log can be thought of being split into two sections of segments: a "clean" section which has previously been cleaned followed by a
 * "dirty" section that has not yet been cleaned. The active log segment is always excluded from cleaning.
 *
 * The cleaning is carried out by a pool of background threads. Each thread chooses the dirtiest log that has the "dedupe" retention policy 
 * and cleans that. The dirtiness of the log is guessed by taking the ratio of bytes in the dirty section of the log to the total bytes in the log. 
 * 
 * To clean a log the cleaner first builds a mapping of key=>last_offset for the dirty section of the log. See kafka.log.OffsetMap for details of
 * the implementation of the mapping. 
 * 
 * Once the key=>offset map is built, the log is cleaned by recopying each log segment but omitting any key that appears in the offset map with a 
 * higher offset than what is found in the segment (i.e. messages with a key that appears in the dirty section of the log).
 * 
 * To avoid segments shrinking to very small sizes with repeated cleanings we implement a rule by which if we will merge successive segments when
 * doing a cleaning if their log and index size are less than the maximum log and index size prior to the clean beginning.
 * 
 * Cleaned segments are swapped into the log as they become available.
 * 
 * One nuance that the cleaner must handle is log truncation. If a log is truncated while it is being cleaned the cleaning of that log is aborted.
 * 
 * Messages with null payload are treated as deletes for the purpose of log compaction. This means that they receive special treatment by the cleaner. 
 * The cleaner will only retain delete records for a period of time to avoid accumulating space indefinitely. This period of time is configurable on a per-topic
 * basis and is measured from the time the segment enters the clean portion of the log (at which point any prior message with that key has been removed).
 * Delete markers in the clean section of the log that are older than this time will not be retained when log segments are being recopied as part of cleaning.
 * 
 * @param config Configuration parameters for the cleaner
 * @param logDirs The directories where offset checkpoints reside
 * @param logs The pool of logs
 * @param time A way to control the passage of time
 */
class LogCleaner(val config: CleanerConfig,
                 val logDirs: Array[File],
                 val logs: Pool[TopicAndPartition, Log], 
                 time: Time = SystemTime) extends Logging {
    
  /* the offset checkpoints holding the last cleaned point for each log */
  private val checkpoints = logDirs.map(dir => (dir, new OffsetCheckpoint(new File(dir, "cleaner-offset-checkpoint")))).toMap
  
  /* the set of logs currently being cleaned */
  private val inProgress = mutable.HashSet[TopicAndPartition]()
  
  /* a global lock used to control all access to the in-progress set and the offset checkpoints */
  private val lock = new Object
    
  /* a counter for creating unique thread names*/
  private val threadId = new AtomicInteger(0)
  
  /* a throttle used to limit the I/O of all the cleaner threads to a user-specified maximum rate */
  private val throttler = new Throttler(desiredRatePerSec = config.maxIoBytesPerSecond, 
                                        checkIntervalMs = 300, 
                                        throttleDown = true, 
                                        time = time)
  
  /* the threads */
  private val cleaners = (0 until config.numThreads).map(_ => new CleanerThread())
  
  /* a hook for testing to synchronize on log cleaning completions */
  private val cleaned = new Semaphore(0)
  
  /**
   * Start the background cleaning
   */
  def startup() {
    info("Starting the log cleaner")
    cleaners.foreach(_.start())
  }
  
  /**
   * Stop the background cleaning
   */
  def shutdown() {
    info("Shutting down the log cleaner.")
    cleaners.foreach(_.interrupt())
    cleaners.foreach(_.join())
  }
  
  /**
   * For testing, a way to know when work has completed. This method blocks until the 
   * cleaner has processed up to the given offset on the specified topic/partition
   */
  def awaitCleaned(topic: String, part: Int, offset: Long, timeout: Long = 30000L): Unit = {
    while(!allCleanerCheckpoints.contains(TopicAndPartition(topic, part)))
      cleaned.tryAcquire(timeout, TimeUnit.MILLISECONDS)
  }
  
  /**
   * @return the position processed for all logs.
   */
  def allCleanerCheckpoints(): Map[TopicAndPartition, Long] = 
    checkpoints.values.flatMap(_.read()).toMap
  
   /**
    * Choose the log to clean next and add it to the in-progress set. We recompute this
    * every time off the full set of logs to allow logs to be dynamically added to the pool of logs
    * the log manager maintains.
    */
  private def grabFilthiestLog(): Option[LogToClean] = {
    lock synchronized {
      val lastClean = allCleanerCheckpoints()      
      val cleanableLogs = logs.filter(l => l._2.config.dedupe)                                     // skip any logs marked for delete rather than dedupe
                              .filterNot(l => inProgress.contains(l._1))                           // skip any logs already in-progress
                              .map(l => LogToClean(l._1, l._2, lastClean.getOrElse(l._1, 0)))      // create a LogToClean instance for each
      val dirtyLogs = cleanableLogs.filter(l => l.totalBytes > 0)                                  // must have some bytes
                                   .filter(l => l.cleanableRatio > l.log.config.minCleanableRatio) // and must meet the minimum threshold for dirty byte ratio
      if(dirtyLogs.isEmpty) {
        None
      } else {
        val filthiest = dirtyLogs.max
        inProgress += filthiest.topicPartition
        Some(filthiest)
      }
    }
  }
  
  /**
   * Save out the endOffset and remove the given log from the in-progress set.
   */
  private def doneCleaning(topicAndPartition: TopicAndPartition, dataDir: File, endOffset: Long) {
    lock synchronized {
      val checkpoint = checkpoints(dataDir)
      val offsets = checkpoint.read() + ((topicAndPartition, endOffset))
      checkpoint.write(offsets)
      inProgress -= topicAndPartition
    }
    cleaned.release()
  }

  /**
   * The cleaner threads do the actual log cleaning. Each thread processes does its cleaning repeatedly by
   * choosing the dirtiest log, cleaning it, and then swapping in the cleaned segments.
   */
  private class CleanerThread extends Thread {
    if(config.dedupeBufferSize / config.numThreads > Int.MaxValue)
      warn("Cannot use more than 2G of cleaner buffer space per cleaner thread, ignoring excess buffer space...")
    val cleaner = new Cleaner(id = threadId.getAndIncrement(),
                              offsetMap = new SkimpyOffsetMap(memory = math.min(config.dedupeBufferSize / config.numThreads, Int.MaxValue).toInt, 
                                                              hashAlgorithm = config.hashAlgorithm),
                              ioBufferSize = config.ioBufferSize / config.numThreads / 2,
                              maxIoBufferSize = config.maxMessageSize,
                              dupBufferLoadFactor = config.dedupeBufferLoadFactor,
                              throttler = throttler,
                              time = time)
    
    setName("kafka-log-cleaner-thread-" + cleaner.id)
    setDaemon(false)

    /**
     * The main loop for the cleaner thread
     */
    override def run() {
      info("Starting cleaner thread %d...".format(cleaner.id))
      try {
        while(!isInterrupted) {
          cleanOrSleep()
        }
      } catch {
        case e: InterruptedException => // all done
        case e: Exception =>
          error("Error in cleaner thread %d:".format(cleaner.id), e)
      }
      info("Shutting down cleaner thread %d.".format(cleaner.id))
    }
    
    /**
     * Clean a log if there is a dirty log available, otherwise sleep for a bit
     */
    private def cleanOrSleep() {
      grabFilthiestLog() match {
        case None =>
          // there are no cleanable logs, sleep a while
          time.sleep(config.backOffMs)
        case Some(cleanable) =>
          // there's a log, clean it
          var endOffset = cleanable.firstDirtyOffset
          try {
            endOffset = cleaner.clean(cleanable)
            logStats(cleaner.id, cleanable.log.name, cleanable.firstDirtyOffset, endOffset, cleaner.stats)
          } catch {
            case e: OptimisticLockFailureException => 
              info("Cleaning of log was aborted due to colliding truncate operation.")
          } finally {
            doneCleaning(cleanable.topicPartition, cleanable.log.dir.getParentFile, endOffset)
          }
      }
    }
    
    /**
     * Log out statistics on a single run of the cleaner.
     */
    def logStats(id: Int, name: String, from: Long, to: Long, stats: CleanerStats) {
      def mb(bytes: Double) = bytes / (1024*1024)
      val message = 
        "%n\tLog cleaner %d cleaned log %s (dirty section = [%d, %d])%n".format(id, name, from, to) + 
        "\t%,.1f MB of log processed in %,.1f seconds (%,.1f MB/sec).%n".format(mb(stats.bytesRead), 
                                                                                stats.elapsedSecs, 
                                                                                mb(stats.bytesRead/stats.elapsedSecs)) + 
        "\tIndexed %,.1f MB in %.1f seconds (%,.1f Mb/sec, %.1f%% of total time)%n".format(mb(stats.mapBytesRead), 
                                                                                           stats.elapsedIndexSecs, 
                                                                                           mb(stats.mapBytesRead)/stats.elapsedIndexSecs, 
                                                                                           100 * stats.elapsedIndexSecs.toDouble/stats.elapsedSecs) + 
        "\tCleaned %,.1f MB in %.1f seconds (%,.1f Mb/sec, %.1f%% of total time)%n".format(mb(stats.bytesRead), 
                                                                                           stats.elapsedSecs - stats.elapsedIndexSecs, 
                                                                                           mb(stats.bytesRead)/(stats.elapsedSecs - stats.elapsedIndexSecs), 100 * (stats.elapsedSecs - stats.elapsedIndexSecs).toDouble/stats.elapsedSecs) + 
        "\tStart size: %,.1f MB (%,d messages)%n".format(mb(stats.bytesRead), stats.messagesRead) +
        "\tEnd size: %,.1f MB (%,d messages)%n".format(mb(stats.bytesWritten), stats.messagesWritten) + 
        "\t%.1f%% size reduction (%.1f%% fewer messages)%n".format(100.0 * (1.0 - stats.bytesWritten.toDouble/stats.bytesRead), 
                                                                   100.0 * (1.0 - stats.messagesWritten.toDouble/stats.messagesRead))
      info(message)
    }
   
  }
}

/**
 * This class holds the actual logic for cleaning a log
 * @param id An identifier used for logging
 * @param offsetMap The map used for deduplication
 * @param bufferSize The size of the buffers to use. Memory usage will be 2x this number as there is a read and write buffer.
 * @param throttler The throttler instance to use for limiting I/O rate.
 * @param time The time instance
 */
private[log] class Cleaner(val id: Int,
                           offsetMap: OffsetMap,
                           ioBufferSize: Int,
                           maxIoBufferSize: Int,
                           dupBufferLoadFactor: Double,
                           throttler: Throttler,
                           time: Time) extends Logging {

  this.logIdent = "Cleaner " + id + ": "
  
  /* stats on this cleaning */
  val stats = new CleanerStats(time)
  
  /* buffer used for read i/o */
  private var readBuffer = ByteBuffer.allocate(ioBufferSize)
  
  /* buffer used for write i/o */
  private var writeBuffer = ByteBuffer.allocate(ioBufferSize)

  /**
   * Clean the given log
   *
   * @param cleanable The log to be cleaned
   *
   * @return The first offset not cleaned
   */
  private[log] def clean(cleanable: LogToClean): Long = {
    stats.clear()
    info("Beginning cleaning of log %s.".format(cleanable.log.name))
    val log = cleanable.log
    val truncateCount = log.numberOfTruncates
    
    // build the offset map
    info("Building offset map for %s...".format(cleanable.log.name))
    val upperBoundOffset = log.activeSegment.baseOffset
    val endOffset = buildOffsetMap(log, cleanable.firstDirtyOffset, upperBoundOffset, offsetMap) + 1
    stats.indexDone()
    
    // figure out the timestamp below which it is safe to remove delete tombstones
    // this position is defined to be a configurable time beneath the last modified time of the last clean segment
    val deleteHorizonMs = 
      log.logSegments(0, cleanable.firstDirtyOffset).lastOption match {
        case None => 0L
        case Some(seg) => seg.lastModified - log.config.deleteRetentionMs
    }
        
    // group the segments and clean the groups
    info("Cleaning log %s (discarding tombstones prior to %s)...".format(log.name, new Date(deleteHorizonMs)))
    for (group <- groupSegmentsBySize(log.logSegments(0, endOffset), log.config.segmentSize, log.config.maxIndexSize))
      cleanSegments(log, group, offsetMap, truncateCount, deleteHorizonMs)
    
    stats.allDone()
    endOffset
  }

  /**
   * Clean a group of segments into a single replacement segment
   *
   * @param log The log being cleaned
   * @param segments The group of segments being cleaned
   * @param map The offset map to use for cleaning segments
   * @param expectedTruncateCount A count used to check if the log is being truncated and rewritten under our feet
   * @param deleteHorizonMs The time to retain delete tombstones
   */
  private[log] def cleanSegments(log: Log, 
                                 segments: Seq[LogSegment], 
                                 map: OffsetMap, 
                                 expectedTruncateCount: Int, 
                                 deleteHorizonMs: Long) {
    // create a new segment with the suffix .cleaned appended to both the log and index name
    val logFile = new File(segments.head.log.file.getPath + Log.CleanedFileSuffix)
    logFile.delete()
    val indexFile = new File(segments.head.index.file.getPath + Log.CleanedFileSuffix)
    indexFile.delete()
    val messages = new FileMessageSet(logFile)
    val index = new OffsetIndex(indexFile, segments.head.baseOffset, segments.head.index.maxIndexSize)
    val cleaned = new LogSegment(messages, index, segments.head.baseOffset, segments.head.indexIntervalBytes, time)

    // clean segments into the new destination segment
    for (old <- segments) {
      val retainDeletes = old.lastModified > deleteHorizonMs
      info("Cleaning segment %s in log %s (last modified %s) into %s, %s deletes."
          .format(old.baseOffset, log.name, new Date(old.lastModified), cleaned.baseOffset, if(retainDeletes) "retaining" else "discarding"))
      cleanInto(old, cleaned, map, retainDeletes)
    }
      
    // trim excess index
    index.trimToValidSize()
    
    // flush new segment to disk before swap
    cleaned.flush()
    
    // update the modification date to retain the last modified date of the original files
    val modified = segments.last.lastModified
    cleaned.lastModified = modified

    // swap in new segment  
    info("Swapping in cleaned segment %d for segment(s) %s in log %s.".format(cleaned.baseOffset, segments.map(_.baseOffset).mkString(","), log.name))
    try {
      log.replaceSegments(cleaned, segments, expectedTruncateCount)
    } catch {
      case e: OptimisticLockFailureException =>
        cleaned.delete()
        throw e  
    }
  }

  /**
   * Clean the given source log segment into the destination segment using the key=>offset mapping
   * provided
   *
   * @param source The dirty log segment
   * @param dest The cleaned log segment
   * @param map The key=>offset mapping
   * @param retainDeletes Should delete tombstones be retained while cleaning this segment
   *
   * TODO: Implement proper compression support
   */
  private[log] def cleanInto(source: LogSegment, dest: LogSegment, map: OffsetMap, retainDeletes: Boolean) {
    var position = 0
    while (position < source.log.sizeInBytes) {
      checkDone()
      // read a chunk of messages and copy any that are to be retained to the write buffer to be written out
      readBuffer.clear()
      writeBuffer.clear()
      val messages = new ByteBufferMessageSet(source.log.readInto(readBuffer, position))
      throttler.maybeThrottle(messages.sizeInBytes)
      // check each message to see if it is to be retained
      var messagesRead = 0
      for (entry <- messages) {
        messagesRead += 1
        val size = MessageSet.entrySize(entry.message)
        position += size
        stats.readMessage(size)
        val key = entry.message.key
        require(key != null, "Found null key in log segment %s which is marked as dedupe.".format(source.log.file.getAbsolutePath))
        val foundOffset = map.get(key)
        /* two cases in which we can get rid of a message:
         *   1) if there exists a message with the same key but higher offset
         *   2) if the message is a delete "tombstone" marker and enough time has passed
         */
        val redundant = foundOffset >= 0 && entry.offset < foundOffset
        val obsoleteDelete = !retainDeletes && entry.message.isNull
        if (!redundant && !obsoleteDelete) {
          ByteBufferMessageSet.writeMessage(writeBuffer, entry.message, entry.offset)
          stats.recopyMessage(size)
        }
      }
      // if any messages are to be retained, write them out
      if(writeBuffer.position > 0) {
        writeBuffer.flip()
        val retained = new ByteBufferMessageSet(writeBuffer)
        dest.append(retained.head.offset, retained)
        throttler.maybeThrottle(writeBuffer.limit)
      }
      
      // if we read bytes but didn't get even one complete message, our I/O buffer is too small, grow it and try again
      if(readBuffer.limit > 0 && messagesRead == 0)
        growBuffers()
    }
    restoreBuffers()
  }
  
  /**
   * Double the I/O buffer capacity
   */
  def growBuffers() {
    if(readBuffer.capacity >= maxIoBufferSize || writeBuffer.capacity >= maxIoBufferSize)
      throw new IllegalStateException("This log contains a message larger than maximum allowable size of %s.".format(maxIoBufferSize))
    val newSize = math.min(this.readBuffer.capacity * 2, maxIoBufferSize)
    info("Growing cleaner I/O buffers from " + readBuffer.capacity + "bytes to " + newSize + " bytes.")
    this.readBuffer = ByteBuffer.allocate(newSize)
    this.writeBuffer = ByteBuffer.allocate(newSize)
  }
  
  /**
   * Restore the I/O buffer capacity to its original size
   */
  def restoreBuffers() {
    if(this.readBuffer.capacity > this.ioBufferSize)
      this.readBuffer = ByteBuffer.allocate(this.ioBufferSize)
    if(this.writeBuffer.capacity > this.ioBufferSize)
      this.writeBuffer = ByteBuffer.allocate(this.ioBufferSize)
  }

  /**
   * Group the segments in a log into groups totaling less than a given size. the size is enforced separately for the log data and the index data.
   * We collect a group of such segments together into a single
   * destination segment. This prevents segment sizes from shrinking too much.
   *
   * @param segments The log segments to group
   * @param maxSize the maximum size in bytes for the total of all log data in a group
   * @param maxIndexSize the maximum size in bytes for the total of all index data in a group
   *
   * @return A list of grouped segments
   */
  private[log] def groupSegmentsBySize(segments: Iterable[LogSegment], maxSize: Int, maxIndexSize: Int): List[Seq[LogSegment]] = {
    var grouped = List[List[LogSegment]]()
    var segs = segments.toList
    while(!segs.isEmpty) {
      var group = List(segs.head)
      var logSize = segs.head.size
      var indexSize = segs.head.index.sizeInBytes
      segs = segs.tail
      while(!segs.isEmpty &&
            logSize + segs.head.size < maxSize &&
            indexSize + segs.head.index.sizeInBytes < maxIndexSize) {
        group = segs.head :: group
        logSize += segs.head.size
        indexSize += segs.head.index.sizeInBytes
        segs = segs.tail
      }
      grouped ::= group.reverse
    }
    grouped.reverse
  }

  /**
   * Build a map of key_hash => offset for the keys in the dirty portion of the log to use in cleaning.
   * @param log The log to use
   * @param start The offset at which dirty messages begin
   * @param end The ending offset for the map that is being built
   * @param map The map in which to store the mappings
   *
   * @return The final offset the map covers
   */
  private[log] def buildOffsetMap(log: Log, start: Long, end: Long, map: OffsetMap): Long = {
    map.clear()
    val dirty = log.logSegments(start, end).toSeq
    info("Building offset map for log %s for %d segments in offset range [%d, %d).".format(log.name, dirty.size, start, end))
    
    // Add all the dirty segments. We must take at least map.slots * load_factor,
    // but we may be able to fit more (if there is lots of duplication in the dirty section of the log)
    var offset = dirty.head.baseOffset
    require(offset == start, "Last clean offset is %d but segment base offset is %d for log %s.".format(start, offset, log.name))
    val minStopOffset = (start + map.slots * this.dupBufferLoadFactor).toLong
    for (segment <- dirty) {
      checkDone()
      if(segment.baseOffset <= minStopOffset || map.utilization < this.dupBufferLoadFactor)
        offset = buildOffsetMap(segment, map)
    }
    info("Offset map for log %s complete.".format(log.name))
    offset
  }

  /**
   * Add the messages in the given segment to the offset map
   *
   * @param segment The segment to index
   * @param map The map in which to store the key=>offset mapping
   *
   * @return The final offset covered by the map
   */
  private def buildOffsetMap(segment: LogSegment, map: OffsetMap): Long = {
    var position = 0
    var offset = segment.baseOffset
    while (position < segment.log.sizeInBytes) {
      checkDone()
      readBuffer.clear()
      val messages = new ByteBufferMessageSet(segment.log.readInto(readBuffer, position))
      throttler.maybeThrottle(messages.sizeInBytes)
      val startPosition = position
      for (entry <- messages) {
        val message = entry.message
        require(message.hasKey)
        val size = MessageSet.entrySize(message)
        position += size
        map.put(message.key, entry.offset)
        offset = entry.offset
        stats.indexMessage(size)
      }
      // if we didn't read even one complete message, our read buffer may be too small
      if(position == startPosition)
        growBuffers()
    }
    restoreBuffers()
    offset
  }

  /**
   * If we aren't running any more throw an AllDoneException
   */
  private def checkDone() {
    if (Thread.currentThread.isInterrupted)
      throw new InterruptedException
  }
}

/**
 * A simple struct for collecting stats about log cleaning
 */
private case class CleanerStats(time: Time = SystemTime) {
  var startTime, mapCompleteTime, endTime, bytesRead, bytesWritten, mapBytesRead, mapMessagesRead, messagesRead, messagesWritten = 0L
  clear()
  
  def readMessage(size: Int) {
    messagesRead += 1
    bytesRead += size
  }
  
  def recopyMessage(size: Int) {
    messagesWritten += 1
    bytesWritten += size
  }
  
  def indexMessage(size: Int) {
    mapMessagesRead += 1
    mapBytesRead += size
  }
  
  def indexDone() {
    mapCompleteTime = time.milliseconds
  }
  
  def allDone() {
    endTime = time.milliseconds
  }
  
  def elapsedSecs = (endTime - startTime)/1000.0
  
  def elapsedIndexSecs = (mapCompleteTime - startTime)/1000.0
  
  def clear() {
    startTime = time.milliseconds
    mapCompleteTime = -1L
    endTime = -1L
    bytesRead = 0L
    bytesWritten = 0L
    mapBytesRead = 0L
    mapMessagesRead = 0L
    messagesRead = 0L
    messagesWritten = 0L
  }
}

/**
 * Helper class for a log, its topic/partition, and the last clean position
 */
private case class LogToClean(topicPartition: TopicAndPartition, log: Log, firstDirtyOffset: Long) extends Ordered[LogToClean] {
  val cleanBytes = log.logSegments(-1, firstDirtyOffset-1).map(_.size).sum
  val dirtyBytes = log.logSegments(firstDirtyOffset, math.max(firstDirtyOffset, log.activeSegment.baseOffset)).map(_.size).sum
  val cleanableRatio = dirtyBytes / totalBytes.toDouble
  def totalBytes = cleanBytes + dirtyBytes
  override def compare(that: LogToClean): Int = math.signum(this.cleanableRatio - that.cleanableRatio).toInt
}