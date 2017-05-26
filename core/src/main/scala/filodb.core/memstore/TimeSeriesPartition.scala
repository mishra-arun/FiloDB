package filodb.core.memstore

import org.jctools.maps.NonBlockingHashMapLong
import org.velvia.filo.{BinaryAppendableVector, BinaryVector, RowReader, RoutingRowReader}
import scalaxy.loops._

import filodb.core.binaryrecord.BinaryRecord
import filodb.core.metadata.RichProjection
import filodb.core.query.{PartitionChunkIndex, ChunkIDPartitionChunkIndex, ChunkSetReader, FiloPartition}
import filodb.core.store.{ChunkSetInfo, timeUUID64}
import filodb.core.Types._

/**
 * A MemStore Partition holding chunks of data for different columns (a schema) for time series use cases.
 * This implies:
 * - New data is assumed to mostly be increasing in time
 * - Thus newer chunks generally contain newer stretches of time
 * - Completed chunks are flushed to disk
 * - Oldest chunks are flushed first
 * - There are no skips or replacing rows.  Everything is append only.  This greatly simplifies the ingestion
 *   engine.
 *
 * Design is for high ingestion rates.
 * Concurrency: single writer for ingest, multiple readers OK for reads
 *
 * This also implements PartitionChunkIndex, and the various chunk indexing methods include returning the
 * very latest data.  However the ChunkSetInfo for the latest chunk still being appended will not have
 * a correct set of starting and ending keys (since they are still changing).
 *
 * TODO: eliminate chunkIDs, that can be stored in the index
 * TODO: document tradeoffs between chunksToKeep, maxChunkSize
 * TODO: update flushedWatermark when flush is confirmed
 */
class TimeSeriesPartition(val projection: RichProjection,
                          val binPartition: PartitionKey,
                          chunksToKeep: Int,
                          maxChunkSize: Int) extends PartitionChunkIndex with FiloPartition {
  import ChunkSetInfo._

  // NOTE: private final compiles down to a field in bytecode, faster than method invocation
  private final val vectors = new NonBlockingHashMapLong[Array[BinaryVector[_]]](32, false)
  private final val chunkIDs = new collection.mutable.Queue[ChunkID]
  // This only holds immutable, finished chunks
  private final val index = new ChunkIDPartitionChunkIndex(binPartition, projection)
  private var currentChunkLen = 0
  private var firstRowKey = BinaryRecord.empty

  private final val numColumns = projection.dataColumns.size
  private final val appenders = MemStore.getAppendables(projection, binPartition, maxChunkSize)
  private final val currentChunks = appenders.map(_.appender)
  private final val rowKeyIndices = projection.rowKeyColIndices.toArray

  // The highest offset of a row that has been committed to disk
  var flushedWatermark = Long.MinValue
  // The highest offset ingested, but probably not committed
  var ingestedWatermark = Long.MinValue

  /**
   * Ingests a new row, adding it to currentChunks IF the offset is greater than the flushedWatermark.
   * The condition ensures data already persisted will not be encoded again.
   */
  def ingest(row: RowReader, offset: Long): Unit = {
    if (offset > flushedWatermark) {
      // Don't create new chunkID until new data appears
      if (currentChunkLen == 0) {
        initNewChunk()
        firstRowKey = makeRowKey(row)
      }
      for { col <- 0 until numColumns optimized } {
        appenders(col).append(row)
      }
      currentChunkLen += 1
      if (ingestedWatermark < offset) ingestedWatermark = offset
      if (currentChunkLen >= maxChunkSize) flush(makeRowKey(row))
    }
  }

  /**
   * Compacts currentChunks and flushes to disk.  When the flush is complete, update the watermark.
   * Gets ready for ingesting a new set of chunks. Keeps chunks in memory until they are aged out.
   * TODO: for partitions getting very little data, in the future, instead of creating a new set of chunks,
   * we might wish to flush current chunks as they are for persistence but then keep adding to the partially
   * filled currentChunks.  That involves much more state, so do much later.
   */
  def flush(lastRowKey: BinaryRecord): Unit = {
    // optimize and compact current chunks
    val frozenVectors = currentChunks.map { curChunk =>
      val optimized = curChunk.optimize()
      curChunk.reset()
      optimized
    }

    val curChunkID = chunkIDs.last
    val chunkInfo = ChunkSetInfo(curChunkID, currentChunkLen, firstRowKey, lastRowKey)
    index.add(chunkInfo, Nil)
    // TODO: push new chunks to ColumnStore

    // replace appendableVectors reference in vectors hash with compacted, immutable chunks
    vectors.put(curChunkID, frozenVectors)

    // Finally, mark current chunk len as 0 so we know to create a new chunkID on next row
    currentChunkLen = 0
  }

  def rowKeyRange(startKey: BinaryRecord, endKey: BinaryRecord): InfosSkipsIt =
    index.rowKeyRange(startKey, endKey) ++ latestChunkIt

  def allChunks: InfosSkipsIt = index.allChunks ++ latestChunkIt

  def singleChunk(startKey: BinaryRecord, id: ChunkID): InfosSkipsIt =
    index.singleChunk(startKey, id)

  def numChunks: Int = chunkIDs.size
  def latestChunkLen: Int = currentChunkLen

  /**
   * Gets the most recent n ChunkSetInfos and skipMaps (which will be empty)
   */
  def newestChunkIds(n: Int): InfosSkipsIt = {
    val latest = latestChunkIt
    val numToTake = if (latest.isEmpty) n else (n - 1)
    index.latestN(numToTake) ++ latest
  }

  // Only returns valid results if there is a current chunk being appended to
  private def latestChunkInfo: ChunkSetInfo =
    ChunkSetInfo(chunkIDs.last, currentChunkLen, firstRowKey, BinaryRecord.empty)

  private def latestChunkIt: InfosSkipsIt =
    if (currentChunkLen > 0) { Iterator.single((latestChunkInfo, emptySkips)) }
    else                     { Iterator.empty }

  private def makeRowKey(row: RowReader): BinaryRecord =
    BinaryRecord(projection.rowKeyBinSchema, RoutingRowReader(row, rowKeyIndices))

  /**
   * Streams back ChunkSetReaders from this Partition as an iterator of readers by chunkID
   * @param infosSkips ChunkSetInfos and skips, as returned by one of the index search methods
   * @param positions an array of the column positions according to projection.dataColumns, ie 0 for the first
   *                  column, up to projection.dataColumns.length - 1
   */
  def readers(infosSkips: InfosSkipsIt, positions: Array[Int]): Iterator[ChunkSetReader] =
    infosSkips.map { case (info, skips) =>
      val vectArray = vectors.get(info.id)
      new ChunkSetReader(info, binPartition, skips, positions.map(vectArray))
    }

  // Initializes vectors, chunkIDs for a new chunkset/chunkID
  private def initNewChunk(): Unit = {
    val newChunkID = timeUUID64
    vectors.put(newChunkID, currentChunks.asInstanceOf[Array[BinaryVector[_]]])
    chunkIDs += newChunkID

    // check number of chunks, flush out old chunk if needed
    if (chunkIDs.length > chunksToKeep) {
      val oldestID = chunkIDs.dequeue // how to remove head element?
      vectors.remove(oldestID)
      index.remove(oldestID)
    }
  }
}