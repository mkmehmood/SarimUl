package com.example.data.backup

import com.example.data.backup.YearCloseMerge.num
import com.example.data.backup.YearCloseMerge.str

class MergeRestorePlan(
  /** Only the backup records that must be written (new or newer than the local copy). */
  val toApply: Map<String, List<Rec>>,
  val added: Int,
  val updated: Int,
  val skipped: Int,
)

class YearCloseRestorePlan(
  /** Complete replacement content for each record collection. */
  val replaceData: Map<String, List<Rec>>,
  /** Merged records created by the close that are not in the backup: delete them everywhere. */
  val mergedToTombstone: List<String>,
)

/**
 * Port of the web restore rules (modules/utilities-sales.js `_doRestoreMerge` / `_doYearCloseRestore`,
 * modules/business.js `compareRecordVersions`, modules/sync.js `mergeDatasets`).
 */
object BackupRestoreLogic {
  /** Web `RECORD_KEYS`: a file is a supported backup if it has at least one of these as an array. */
  val RECORD_KEYS = listOf(
    "production", "sales", "calculator", "rep", "clients", "customers",
    "transactions", "entities", "inventory", "factory", "expenses", "returns",
  )
  private val POST_CLOSE_KEYS = listOf("production", "calculator", "sales", "rep", "transactions", "factory", "returns", "expenses")

  fun isSupportedBackup(arrayKeys: Set<String>): Boolean = RECORD_KEYS.any { it in arrayKeys }

  /** `updatedAt || timestamp || createdAt` as epoch millis (numbers, {seconds}, or ISO strings). */
  fun versionMs(r: Rec?): Double {
    if (r == null) return 0.0
    for (k in listOf("updatedAt", "timestamp", "createdAt")) {
      val v = r[k]
      val ms = toMs(v)
      if (ms != 0.0) return ms
    }
    return 0.0
  }

  private fun toMs(v: Any?): Double = when (v) {
    null -> 0.0
    is Number -> v.toDouble()
    is Map<*, *> -> {
      val s = (v["seconds"] ?: v["_seconds"]) as? Number
      val n = (v["nanoseconds"] ?: v["_nanoseconds"]) as? Number
      if (s != null) s.toDouble() * 1000.0 + Math.round((n?.toDouble() ?: 0.0) / 1e6) else 0.0
    }
    is String -> v.toDoubleOrNull() ?: runCatching { java.time.Instant.parse(v).toEpochMilli().toDouble() }.getOrDefault(0.0)
    else -> 0.0
  }

  private fun recTs(r: Rec): Double {
    val c = num(r["createdAt"]); if (c != 0.0) return c
    return num(r["timestamp"])
  }

  private fun id(r: Rec?): String = if (r == null) "" else str(r["id"])

  /** Keep one record per id; the newer one wins (ties keep the first seen), like `deduplicateByUUID`. */
  fun dedupeById(list: List<Rec>): List<Rec> {
    val seen = linkedMapOf<String, Rec>()
    for (r in list) {
      val i = id(r); if (i.isEmpty()) continue
      val prev = seen[i]
      if (prev == null || versionMs(r) - versionMs(prev) > 0) seen[i] = r
    }
    return seen.values.toList()
  }

  /** Web `mergeDatasets(localArray, cloudArray)`: union by id; [local] wins when it is at least as new. */
  fun mergeDatasets(local: List<Rec>, cloud: List<Rec>, deleted: Set<String> = emptySet()): List<Rec> {
    val merged = linkedMapOf<String, Rec>()
    cloud.forEach { r -> val i = id(r); if (i.isNotEmpty() && i !in deleted) merged[i] = r }
    local.forEach { l ->
      val i = id(l); if (i.isEmpty() || i in deleted) return@forEach
      val c = merged[i]
      if (c == null) merged[i] = l else if (versionMs(l) >= versionMs(c)) merged[i] = l
    }
    return merged.values.toList()
  }

  /** Merge-restore: records are merged, not overwritten. Deleted ids stay deleted; newer wins. */
  fun planMergeRestore(local: Map<String, List<Rec>>, backup: Map<String, List<Rec>>, deleted: Set<String>): MergeRestorePlan {
    var added = 0; var updated = 0; var skipped = 0
    val out = linkedMapOf<String, List<Rec>>()
    for (key in RECORD_KEYS) {
      val localById = local[key].orEmpty().associateBy { id(it) }
      val apply = mutableListOf<Rec>()
      for (b in dedupeById(backup[key].orEmpty()).filter { id(it).isNotEmpty() && id(it) !in deleted }) {
        val l = localById[id(b)]
        when {
          l == null -> { added++; apply += b }
          versionMs(b) - versionMs(l) > 0 -> { updated++; apply += b }
          else -> skipped++
        }
      }
      if (apply.isNotEmpty()) out[key] = apply
    }
    return MergeRestorePlan(out, added, updated, skipped)
  }

  /** Records in the backup that were deleted on this device after the year-close backup was taken. */
  fun postCloseDeletions(backup: Map<String, List<Rec>>, deleted: Set<String>): Int =
    RECORD_KEYS.sumOf { k -> backup[k].orEmpty().count { id(it).isNotEmpty() && id(it) in deleted } }

  /**
   * Reverse a year close: put the pre-close snapshot back and drop the merged opening-balance records,
   * keeping only genuinely new records created after the backup was taken.
   */
  fun planYearCloseRestore(
    current: Map<String, List<Rec>>,
    backup: Map<String, List<Rec>>,
    deleted: Set<String>,
    honourPostCloseDeletions: Boolean,
    backupCreatedAt: Long,
    repNames: Set<String>,
  ): YearCloseRestorePlan {
    val backupIds = POST_CLOSE_KEYS.flatMap { backup[it].orEmpty() }.map { id(it) }.filter { it.isNotEmpty() }.toSet()
    val toTombstone = POST_CLOSE_KEYS.flatMap { current[it].orEmpty() }
      .filter { id(it).isNotEmpty() && YearCloseMerge.isMerged(it) && id(it) !in backupIds }
      .map { id(it) }
    val allDeleted = deleted + toTombstone
    val alive: (Rec) -> Boolean = if (honourPostCloseDeletions) { r -> id(r).isNotEmpty() && id(r) !in allDeleted } else { r -> id(r).isNotEmpty() }
    val lowerReps = repNames.map { it.lowercase() }.toSet()
    val notRepName: (Rec) -> Boolean = { r -> str(r["name"]).isEmpty() || str(r["name"]).lowercase() !in lowerReps }

    fun postCloseKeep(cur: List<Rec>, backupArr: List<Rec>): List<Rec> {
      val ids = backupArr.map { id(it) }.filter { it.isNotEmpty() }.toSet()
      return cur.filter { r ->
        alive(r) && id(r).isNotEmpty() && recTs(r) > backupCreatedAt && id(r) !in ids &&
          !(YearCloseMerge.isMerged(r) && id(r) !in backupIds)
      }
    }

    val out = linkedMapOf<String, List<Rec>>()
    for (key in RECORD_KEYS) {
      val b = backup[key].orEmpty().filter(alive)
      val cur = current[key].orEmpty()
      out[key] = when (key) {
        "clients" -> mergeDatasets(b, cur.filter(alive))
        "customers" -> mergeDatasets(b.filter(notRepName), cur.filter(alive).filter(notRepName))
        "inventory", "entities" -> b
        else -> b + postCloseKeep(cur, backup[key].orEmpty())
      }.let { list ->
        val m = linkedMapOf<String, Rec>(); list.forEach { r -> if (id(r).isNotEmpty()) m[id(r)] = r }; m.values.toList()
      }
    }
    return YearCloseRestorePlan(out, toTombstone)
  }
}
