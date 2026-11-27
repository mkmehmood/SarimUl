package com.example.data.model

import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

/**
 * Pure-Kotlin port of the enriched UUIDv3/v5-hybrid identity, device-shard,
 * validation, metadata extraction, and semantic conflict-resolution system
 * from sarim_ref (`modules/business.js`, `modules/sync.js`, `tests/delta-sync.test.js`).
 */
data class UuidMeta(
  val deviceShard: String,
  val v5entropy: String,
  val timestampMs: Long,
  val appMode: String,
  val sequence: Int,
  val isEnriched: Boolean = true,
  val version: Int = 3,
)

data class ConflictRecordSnapshot(
  val id: String,
  val updatedAt: Long = 0L,
  val timestamp: Long = 0L,
  val createdAt: Long = 0L,
  val syncPending: Boolean = false,
  // Calculator financial guard
  val totalSoldKg: Double = 0.0,
  val grossRevenue: Double = 0.0,
  // Production return / formula guard
  val isReturn: Boolean = false,
  val formulaUnitsUsed: Double = 0.0,
  val totalFormulaCost: Double = 0.0,
  // Inventory / purchase supplier & payment guard
  val supplierId: String? = null,
  val paymentStatus: String = "",
)

object SarimUuid {
  // Prefixes matching sarim_ref across all collections
  const val PREFIX_PROD = "prod"
  const val PREFIX_SALE = "sale"
  const val PREFIX_CALC = "calc"
  const val PREFIX_CUST = "cust"
  const val PREFIX_REP_CUST = "rep_cust"
  const val PREFIX_OLD_DEBT = "old_debt"
  const val PREFIX_MAT = "mat"
  const val PREFIX_LOT = "lot"
  const val PREFIX_SUPP = "supp"
  const val PREFIX_PAY = "pay"
  const val PREFIX_FPROD = "fprod"
  const val PREFIX_DAILY_LEDGER = "dl_"
  const val PREFIX_ENT = "ent"
  const val PREFIX_EXP = "exp"
  const val PREFIX_RET = "ret"
  const val PREFIX_RETLOG = "retlog"
  const val PREFIX_TRFPAIR = "trfpair"
  const val PREFIX_TRF = "trf"
  const val PREFIX_FORMULA = "formula"
  const val PREFIX_MIX = "mix"
  const val PREFIX_OB = "ob"
  const val PREFIX_ACCT = "acct"
  const val PREFIX_GRP = "grp"
  const val PREFIX_REPAIR = "repair"
  const val PREFIX_RECOVERED = "recovered"

  const val MODE_ADMIN = "admin"
  const val MODE_REP = "rep"
  const val MODE_PRODUCTION = "production"
  const val MODE_FACTORY = "factory"
  const val MODE_USERROLE = "userrole"

  private const val V3_TS_MIN = 1_577_836_800_000L // 2020-01-01T00:00:00Z
  private const val V3_TS_MAX = 4_102_358_400_000L // 2100-01-01T00:00:00Z
  private const val CLOCK_SKEW_TOLERANCE_MS = 5L * 60L * 1000L

  val VALID_APP_MODES = setOf(MODE_ADMIN, MODE_REP, MODE_PRODUCTION, MODE_FACTORY, MODE_USERROLE)

  val MODE_CODES = mapOf(
    MODE_ADMIN to '0',
    MODE_REP to '1',
    MODE_PRODUCTION to '2',
    MODE_FACTORY to '3',
    MODE_USERROLE to '4',
  )

  val MODE_LABELS = mapOf(
    '0' to MODE_ADMIN,
    '1' to MODE_REP,
    '2' to MODE_PRODUCTION,
    '3' to MODE_FACTORY,
    '4' to MODE_USERROLE,
  )

  private val UUID_V5_NS = byteArrayOf(
    0x6b, 0xa7.toByte(), 0xb8.toByte(), 0x10,
    0x9d.toByte(), 0xad.toByte(), 0x11, 0xd1.toByte(),
    0x80.toByte(), 0xb4.toByte(), 0x00, 0xc0.toByte(),
    0x4f, 0xd4.toByte(), 0x30, 0xc8.toByte(),
  )

  private val STANDARD_UUID_REGEX = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    RegexOption.IGNORE_CASE,
  )

  private val PREFIXED_UUID_REGEX = Regex(
    "^[a-z0-9][a-z0-9_-]*-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}(_\\d+)?$",
    RegexOption.IGNORE_CASE,
  )

  private val DEVICE_ID_REGEX = Regex(
    "^dev-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_(\\d{13})$",
    RegexOption.IGNORE_CASE,
  )

  @Volatile var currentAppMode: String = MODE_ADMIN
  @Volatile var currentDeviceId: String? = null
  @Volatile var currentDeviceShard: String = "0000"

  private var uuidLastMs: Long = 0L
  private var uuidSeq: Int = 0
  private val seqLock = Any()

  fun deriveAppMode(roles: Collection<String>): String {
    val clean = roles.map { it.trim().lowercase() }.toSet()
    return when {
      MODE_ADMIN in clean || clean.isEmpty() -> MODE_ADMIN
      clean == setOf(MODE_REP) -> MODE_REP
      clean == setOf(MODE_PRODUCTION) -> MODE_PRODUCTION
      clean == setOf(MODE_FACTORY) -> MODE_FACTORY
      else -> MODE_USERROLE
    }
  }

  /** Exact 32-bit FNV-1a lower 16 bits -> 4 lowercase hex chars. */
  fun deriveDeviceShard(did: String?): String {
    if (did.isNullOrBlank()) return "0000"
    var h = 0x811c9dc5u
    for (i in did.indices) {
      h = h xor did[i].code.toUInt()
      h = h * 0x01000193u
    }
    return (h and 0xffffu).toString(16).padStart(4, '0')
  }

  fun initDeviceShard(deviceId: String?): String {
    currentDeviceId = deviceId?.takeIf { it.isNotBlank() }
    val shard = deriveDeviceShard(currentDeviceId)
    currentDeviceShard = shard
    return shard
  }

  fun clearDeviceState() {
    currentDeviceId = null
    currentDeviceShard = "0000"
  }

  fun newDeviceId(nowMs: Long = System.currentTimeMillis()): String {
    return "dev-${UUID.randomUUID()}_$nowMs"
  }

  fun isValidDeviceId(did: String?, loginTs: Long? = null): Boolean {
    if (did.isNullOrBlank()) return false
    val m = DEVICE_ID_REGEX.matchEntire(did) ?: return false
    val devTs = m.groupValues[1].toLongOrNull() ?: return false
    if (loginTs != null && loginTs > 0L && devTs < loginTs - 60_000L) return false
    return true
  }

  private fun randomByte(): Int = (UUID.randomUUID().leastSignificantBits and 0xffL).toInt()

  private fun nextSeq(nowMs: Long): Pair<Long, Int> = synchronized(seqLock) {
    if (nowMs > uuidLastMs) {
      uuidLastMs = nowMs
      uuidSeq = randomByte()
    } else {
      uuidSeq = (uuidSeq + 1) and 0xff
      if (uuidSeq == 0) {
        uuidLastMs += 1L
        uuidSeq = randomByte()
      }
    }
    uuidLastMs to uuidSeq
  }

  private fun buildUUIDv3Base(): ByteArray {
    val name = UUID.randomUUID().toString()
    val nameBytes = name.toByteArray(Charsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update(UUID_V5_NS)
    digest.update(nameBytes)
    val hash = digest.digest()
    val b = ByteArray(16)
    System.arraycopy(hash, 0, b, 0, 16)
    b[6] = ((b[6].toInt() and 0x0f) or 0x50).toByte()
    b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
    return b
  }

  fun generateUUID(
    prefix: String = "",
    retryCount: Int = 0,
    tsMs: Long? = null,
    modeOverride: String? = null,
  ): String {
    val maxRetries = 3
    val nowMs = tsMs ?: System.currentTimeMillis()
    val (ts, seq) = nextSeq(nowMs)
    val base = buildUUIDv3Base()

    val tsHi32 = (ts ushr 16)
    base[0] = ((tsHi32 ushr 24) and 0xffL).toByte()
    base[1] = ((tsHi32 ushr 16) and 0xffL).toByte()
    base[2] = ((tsHi32 ushr 8) and 0xffL).toByte()
    base[3] = (tsHi32 and 0xffL).toByte()

    val tsLo16 = ts and 0xffffL
    base[4] = ((tsLo16 ushr 8) and 0xffL).toByte()
    base[5] = (tsLo16 and 0xffL).toByte()

    val modeChar = if (!modeOverride.isNullOrBlank()) {
      MODE_CODES[modeOverride.lowercase()] ?: modeOverride.first()
    } else {
      MODE_CODES[currentAppMode] ?: '0'
    }
    val modeNib = modeChar.digitToIntOrNull(16)?.and(0x0f) ?: 0

    base[6] = (0x40 or ((seq ushr 4) and 0x0f)).toByte()
    base[7] = (((seq and 0x0f) shl 4) or modeNib).toByte()
    base[8] = ((base[8].toInt() and 0x3f) or 0x80).toByte()

    val shard = currentDeviceShard.toIntOrNull(16) ?: 0
    base[10] = ((shard ushr 8) and 0xff).toByte()
    base[11] = (shard and 0xff).toByte()

    val hex = buildString(32) {
      for (byte in base) {
        val v = byte.toInt() and 0xff
        append(HEX_CHARS[v ushr 4])
        append(HEX_CHARS[v and 0x0f])
      }
    }
    val uuid = "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    val finalUuid = if (prefix.isNotBlank()) "$prefix-$uuid" else uuid
    if (retryCount < maxRetries && !validateUUID(finalUuid)) {
      return generateUUID(prefix, retryCount + 1, tsMs, modeOverride)
    }
    return finalUuid
  }

  private val HEX_CHARS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')

  fun validateUUID(uuid: String?): Boolean {
    if (uuid.isNullOrBlank()) return false
    return STANDARD_UUID_REGEX.matches(uuid) || PREFIXED_UUID_REGEX.matches(uuid)
  }

  fun extractUUIDMeta(uuid: String?): UuidMeta? {
    if (uuid.isNullOrBlank() || !validateUUID(uuid)) return null
    val coreRaw = uuid.substringBeforeLast('_', uuid).takeIf { raw ->
      uuid.contains('_') && uuid.substringAfterLast('_').all { it.isDigit() }
    } ?: uuid
    val allParts = coreRaw.split('-')
    if (allParts.size < 5) return null
    val coreParts = allParts.subList(allParts.size - 5, allParts.size)
    val cp1 = coreParts[0].lowercase()
    val cp2 = coreParts[1].lowercase()
    val cp3 = coreParts[2].lowercase()
    val node = coreParts[4].lowercase()
    if (cp1.length != 8 || cp2.length != 4 || cp3.length != 4 || node.length != 12) return null
    val modeNib = cp3[3]
    val appMode = MODE_LABELS[modeNib] ?: return null
    val tsHi = cp1.toLongOrNull(16) ?: return null
    val tsLo = cp2.toLongOrNull(16) ?: return null
    val tsMs = (tsHi shl 16) + tsLo
    if (tsMs !in V3_TS_MIN..V3_TS_MAX) return null
    val seqHi = cp3[1].digitToIntOrNull(16) ?: return null
    val seqLo = cp3[2].digitToIntOrNull(16) ?: return null
    val seq = (seqHi shl 4) or seqLo
    val deviceShard = node.substring(0, 4)
    val v5entropy = node.substring(4, 12)
    return UuidMeta(
      deviceShard = deviceShard,
      v5entropy = v5entropy,
      timestampMs = tsMs,
      appMode = appMode,
      sequence = seq,
      isEnriched = true,
      version = 3,
    )
  }

  fun effectiveRecordTime(updatedAt: Long, timestamp: Long, createdAt: Long): Long {
    return when {
      updatedAt > 0L -> updatedAt
      timestamp > 0L -> timestamp
      createdAt > 0L -> createdAt
      else -> 0L
    }
  }

  fun validateTimestamp(ts: Long, allowFuture: Boolean = false, nowMs: Long = System.currentTimeMillis()): Boolean {
    if (ts !in 946_684_800_000L..4_102_444_800_000L) return false
    if (!allowFuture && ts > nowMs + CLOCK_SKEW_TOLERANCE_MS) return false
    return true
  }

  /**
   * Returns >0 if A is newer than B, <0 if B is newer than A, 0 if tied.
   */
  fun compareRecordVersions(
    idA: String,
    updatedAtA: Long,
    timestampA: Long,
    createdAtA: Long,
    idB: String,
    updatedAtB: Long,
    timestampB: Long,
    createdAtB: Long,
  ): Int {
    val fieldA = effectiveRecordTime(updatedAtA, timestampA, createdAtA)
    val fieldB = effectiveRecordTime(updatedAtB, timestampB, createdAtB)
    if (fieldA != fieldB) {
      return if (fieldA > fieldB) 1 else -1
    }
    val metaA = extractUUIDMeta(idA)
    val metaB = extractUUIDMeta(idB)
    if (metaA != null && metaB != null) {
      if (metaA.timestampMs != metaB.timestampMs) {
        return if (metaA.timestampMs > metaB.timestampMs) 1 else -1
      }
      if (metaA.sequence != metaB.sequence) {
        return if (metaA.sequence > metaB.sequence) 1 else -1
      }
      if (metaA.deviceShard != metaB.deviceShard) {
        return metaA.deviceShard.compareTo(metaB.deviceShard)
      }
      return 0
    }
    if (metaA != null && metaB == null) return 1
    if (metaA == null && metaB != null) return -1
    return 0
  }

  /**
   * Port of semantic conflict guards from `modules/sync.js` & `tests/delta-sync.test.js`:
   * returns true if LOCAL must be kept over REMOTE for the given record pair.
   */
  fun shouldLocalWinConflict(
    collection: String,
    local: ConflictRecordSnapshot?,
    remote: ConflictRecordSnapshot,
  ): Boolean {
    if (local == null) return false
    if (local.syncPending) return true

    // Calculator settlement financial protection
    if (collection == "calculator") {
      val localHasFinancial = abs(local.totalSoldKg) > 0.0001 || abs(local.grossRevenue) > 0.0001
      val remoteZeroed = abs(remote.totalSoldKg) <= 0.0001 && abs(remote.grossRevenue) <= 0.0001
      if (localHasFinancial && remoteZeroed) return true
    }

    // Production guards: protect return flag & non-zero formula units/cost
    if (collection == "production") {
      if (local.isReturn && !remote.isReturn) return true
      if ((local.formulaUnitsUsed > 0.0 && remote.formulaUnitsUsed <= 0.0) ||
        (local.totalFormulaCost > 0.0 && remote.totalFormulaCost <= 0.0)
      ) {
        return true
      }
    }

    // Inventory guards: protect supplier link & settled payment status
    if (collection == "inventory") {
      if (!local.supplierId.isNullOrBlank() && remote.supplierId.isNullOrBlank()) return true
      if (local.paymentStatus.equals("paid", ignoreCase = true) &&
        !remote.paymentStatus.equals("paid", ignoreCase = true)
      ) {
        return true
      }
    }

    // Standard timestamp / UUID meta comparison (remote >= local -> remote wins -> return false)
    val cmp = compareRecordVersions(
      idA = local.id,
      updatedAtA = local.updatedAt,
      timestampA = local.timestamp,
      createdAtA = local.createdAt,
      idB = remote.id,
      updatedAtB = remote.updatedAt,
      timestampB = remote.timestamp,
      createdAtB = remote.createdAt,
    )
    return cmp > 0
  }
}
