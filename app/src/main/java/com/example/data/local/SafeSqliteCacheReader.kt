package com.example.data.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Safely reads SQLite/Room rows that may contain large text/JSON blobs (`kv_store`, `photos`,
 * `ndapp_outbox`, `deletions`) without exceeding Android's ~2 MB CursorWindow limit
 * (`SQLiteBlobTooBigException`).
 */
class SafeSqliteCacheReader(private val dao: SarimDao) {

  suspend fun getAllKvOnce(): List<KvStoreEntity> {
    val metas = dao.getKvMetaAll()
    if (metas.isEmpty()) return emptyList()
    if (metas.all { it.isValueNull || it.byteLen <= SAFE_DIRECT_BYTE_LIMIT }) {
      val fast = runCatchingExceptCancel { dao.getAllKv() }
      if (fast != null && fast.size == metas.size) return fast
    }
    return metas.map { meta -> materializeKv(meta) }
  }

  fun observeAllKv(): Flow<List<KvStoreEntity>> =
    dao.observeAllKvKeys().map { getAllKvOnce() }

  suspend fun getKvOnce(fullKey: String): KvStoreEntity? {
    val meta = dao.getKvMetaByFullKey(fullKey) ?: return null
    if (meta.isValueNull || meta.byteLen <= SAFE_DIRECT_BYTE_LIMIT) {
      val fast = runCatchingExceptCancel { dao.getKv(fullKey) }
      if (fast != null) return fast
    }
    return materializeKv(meta)
  }

  suspend fun getKvByUserKeyOnce(userKey: String): KvStoreEntity? {
    val meta = dao.getKvMetaByUserKey(userKey) ?: return null
    if (meta.isValueNull || meta.byteLen <= SAFE_DIRECT_BYTE_LIMIT) {
      val fast = runCatchingExceptCancel { dao.getKvByUserKey(userKey) }
      if (fast != null) return fast
    }
    return materializeKv(meta)
  }

  suspend fun getOutboxAllOnce(): List<NdappOutboxEntity> {
    val metas = dao.getOutboxMetaAll()
    if (metas.isEmpty()) return emptyList()
    if (metas.all { it.isPayloadNull || it.byteLen <= SAFE_DIRECT_BYTE_LIMIT }) {
      val fast = runCatchingExceptCancel { dao.getOutboxAll() }
      if (fast != null && fast.size == metas.size) return fast
    }
    return metas.map { meta ->
      val payload = when {
        meta.isPayloadNull -> null
        meta.charLen == 0 -> ""
        else -> readTextInChunks(meta.charLen) { pos, len ->
          dao.readOutboxPayloadChunk(meta.id, pos, len)
        }
      }
      NdappOutboxEntity(
        id = meta.id,
        uid = meta.uid,
        action = meta.action,
        collection = meta.collection,
        docId = meta.docId,
        payload = payload,
        createdAt = meta.createdAt,
        attempts = meta.attempts,
        lastAttempt = meta.lastAttempt,
      )
    }
  }

  suspend fun getPendingPhotosOnce(): List<PhotoRecordEntity> {
    val metas = dao.getPendingPhotoMeta()
    if (metas.isEmpty()) return emptyList()
    return metas.map { meta -> materializePhoto(meta) }
  }

  suspend fun getAllPhotosOnce(): List<PhotoRecordEntity> {
    val metas = dao.getPhotoMetaAll()
    if (metas.isEmpty()) return emptyList()
    if (metas.all { it.isDataNull || it.byteLen <= SAFE_DIRECT_BYTE_LIMIT }) {
      val fast = runCatchingExceptCancel { dao.getAllPhotos() }
      if (fast != null && fast.size == metas.size) return fast
    }
    return metas.map { meta -> materializePhoto(meta) }
  }

  suspend fun getUnsyncedDeletionsOnce(): List<DeletionTombstoneEntity> {
    val metas = dao.getUnsyncedDeletionMeta()
    if (metas.isEmpty()) return emptyList()
    return metas.map { meta ->
      val snapshot = when {
        meta.isSnapshotNull -> null
        meta.charLen == 0 -> ""
        else -> readTextInChunks(meta.charLen) { pos, len ->
          dao.readDeletionSnapshotChunk(meta.id, pos, len)
        }
      }
      DeletionTombstoneEntity(
        id = meta.id,
        recordId = meta.recordId,
        collection = meta.collection,
        recordType = meta.recordType,
        deletedAt = meta.deletedAt,
        expiresAt = meta.expiresAt,
        syncedToCloud = meta.syncedToCloud,
        displayName = meta.displayName,
        displayDetail = meta.displayDetail,
        displayAmount = meta.displayAmount,
        snapshotJson = snapshot,
        deletedBy = meta.deletedBy,
        deletionVersion = meta.deletionVersion,
        groupId = meta.groupId,
      )
    }
  }

  private suspend fun materializeKv(meta: KvMetaRow): KvStoreEntity {
    val value = when {
      meta.isValueNull -> null
      meta.charLen == 0 -> ""
      else -> readTextInChunks(meta.charLen) { pos, len ->
        dao.readKvValueChunk(meta.fullKey, pos, len)
      }
    }
    return KvStoreEntity(
      fullKey = meta.fullKey,
      userKey = meta.userKey,
      uid = meta.uid,
      collection = meta.collection,
      rowType = meta.rowType,
      encrypted = meta.encrypted,
      value = value,
      ts = meta.ts,
      createdAt = meta.createdAt,
    )
  }

  /** Reads one photo's data by key, in chunks so large web-captured photos never overflow the cursor window. */
  suspend fun getPhotoDataByKey(photoKey: String): String? {
    val len = runCatchingExceptCancel { dao.getPhotoCharLength(photoKey) } ?: return null
    if (len <= 0) return null
    return runCatchingExceptCancel {
      readTextInChunks(len) { pos, l -> dao.readPhotoDataChunk(photoKey, pos, l) }
    }
  }

  private suspend fun materializePhoto(meta: PhotoMetaRow): PhotoRecordEntity {
    val data = when {
      meta.isDataNull -> null
      meta.charLen == 0 -> ""
      else -> readTextInChunks(meta.charLen) { pos, len ->
        dao.readPhotoDataChunk(meta.photoKey, pos, len)
      }
    }
    return PhotoRecordEntity(
      photoKey = meta.photoKey,
      docId = meta.docId,
      data = data,
      deleted = meta.deleted,
      updatedAt = meta.updatedAt,
      syncPending = meta.syncPending,
    )
  }

  private inline fun <T> runCatchingExceptCancel(block: () -> T): T? {
    return try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
  }

  companion object {
    const val SAFE_DIRECT_BYTE_LIMIT = 750_000
    const val DEFAULT_CHUNK_CHARS = 200_000
    const val MIN_CHUNK_CHARS = 4_096

    suspend fun readTextInChunks(
      totalChars: Int,
      initialChunkChars: Int = DEFAULT_CHUNK_CHARS,
      minChunkChars: Int = MIN_CHUNK_CHARS,
      readSlice: suspend (startPos: Int, len: Int) -> String?,
    ): String {
      if (totalChars <= 0) return ""
      val sb = StringBuilder(totalChars)
      var pos = 1
      var chunkSize = initialChunkChars.coerceAtLeast(minChunkChars)
      while (pos <= totalChars) {
        val len = minOf(chunkSize, totalChars - pos + 1)
        val piece = try {
          readSlice(pos, len)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          if (isBlobTooBigError(e) && chunkSize > minChunkChars) {
            chunkSize = (chunkSize / 2).coerceAtLeast(minChunkChars)
            continue
          }
          throw e
        }
        if (piece.isNullOrEmpty()) break
        sb.append(piece)
        pos += piece.length
      }
      return sb.toString()
    }

    internal fun isBlobTooBigError(t: Throwable): Boolean {
      var cur: Throwable? = t
      while (cur != null) {
        val name = cur.javaClass.simpleName
        val msg = cur.message.orEmpty()
        if (name == "SQLiteBlobTooBigException" ||
          msg.contains("CursorWindow", ignoreCase = true) ||
          msg.contains("Row too big", ignoreCase = true)
        ) {
          return true
        }
        cur = cur.cause
      }
      return false
    }
  }
}
