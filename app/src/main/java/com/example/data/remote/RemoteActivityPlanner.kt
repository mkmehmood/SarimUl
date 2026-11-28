package com.example.data.remote

import com.example.data.local.DailyLedgerEntity
import com.example.data.local.SarimDao
import com.example.util.SarimFormat

/**
 * Pure-Kotlin port of the web app's `modules/remote-activity.js`.
 * Diffs a baseline snapshot of known record IDs against newly arrived cloud records
 * and generates user-friendly local notifications (skipping own device shard,
 * merged year-end records, placeholders, and entries older than 24 hours).
 */
data class RemoteActivityRecord(
  val id: String,
  val collection: String,
  val createdAt: Long = 0L,
  val timestamp: Long = 0L,
  val isMerged: Boolean = false,
  val isDeleted: Boolean = false,
  val author: String = "",
  val salesRep: String = "",
  val isCollection: Boolean = false,
  val isTransfer: Boolean = false,
  val transferDirection: String = "",
  val isReturn: Boolean = false,
  val isExpense: Boolean = false,
  val customerName: String = "",
  val name: String = "",
  val entityName: String = "",
  val description: String = "",
  val category: String = "",
  val party: String = "",
  val formulaName: String = "",
  val type: String = "",
  val mode: String = "",
  val toMode: String = "",
  val seller: String = "",
  val amount: Double = 0.0,
  val totalValue: Double = 0.0,
  val netKg: Double = 0.0,
  val units: Double = 0.0,
  val revenue: Double = 0.0,
)

data class RemoteActivityAlert(
  val title: String,
  val body: String,
  val targetKey: String,
  val targetId: String,
  val tag: String,
)

object RemoteActivityPlanner {
  const val REMOTE_WINDOW_MS: Long = 24L * 60L * 60L * 1000L
  const val REMOTE_MAX_NOTIFICATIONS: Int = 5

  val REMOTE_KEYS = listOf(
    "rep",
    "sales",
    "transactions",
    "expenses",
    "production",
    "factory",
    "clients",
    "calculator",
    "daily_ledger",
  )

  val OPENABLE_KEYS = setOf(
    "sales",
    "rep",
    "transactions",
    "expenses",
    "production",
    "factory",
    "clients",
    "calculator",
    "daily_ledger",
  )

  fun describeRemote(
    record: RemoteActivityRecord,
    formatMoney: (Double) -> String = { SarimFormat.formatRs(it) },
    nowMs: Long = System.currentTimeMillis(),
  ): RemoteActivityAlert? {
    if (record.isDeleted || record.id.isBlank() || record.isMerged || record.id == "_placeholder_") {
      return null
    }
    val key = record.collection
    val created = record.createdAt.takeIf { it > 0L } ?: record.timestamp
    if (created <= 0L || (nowMs - created) > REMOTE_WINDOW_MS) {
      return null
    }

    val targetKey = if (key in OPENABLE_KEYS) key else ""
    val targetId = if (targetKey.isNotEmpty()) record.id else ""
    val tag = "tx-${record.id}"

    if (key == "rep") {
      val rep = record.salesRep.trim()
      if (rep.isEmpty() || rep.equals("NONE", ignoreCase = true) || rep.equals("admin", ignoreCase = true)) {
        return null
      }
      val cust = record.customerName.ifBlank { "customer" }
      return if (record.isCollection) {
        RemoteActivityAlert(
          title = "New collection",
          body = "$rep collected ${formatMoney(record.totalValue)} from $cust",
          targetKey = targetKey,
          targetId = targetId,
          tag = tag,
        )
      } else {
        RemoteActivityAlert(
          title = "New sale",
          body = "$rep sold to $cust — ${formatMoney(record.totalValue)}",
          targetKey = targetKey,
          targetId = targetId,
          tag = tag,
        )
      }
    }

    if (key == "clients") {
      val rep = record.salesRep.trim()
      if (rep.isEmpty() || rep.equals("NONE", ignoreCase = true) || rep.equals("admin", ignoreCase = true) || record.name.isBlank()) {
        return null
      }
      return RemoteActivityAlert(
        title = "New customer",
        body = "$rep added customer ${record.name}",
        targetKey = targetKey,
        targetId = targetId,
        tag = tag,
      )
    }

    if (key == "daily_ledger") {
      val who = record.author.trim().ifBlank { "A teammate" }
      val amtStr = formatMoney(record.amount)
      val body = if (record.type.equals("TRANSFER", ignoreCase = true)) {
        "$who moved $amtStr from ${record.mode} to ${record.toMode}"
      } else {
        val kind = if (record.type.equals("OUT", ignoreCase = true)) "cash out" else "cash in"
        val catPart = if (record.category.isNotBlank()) " — ${record.category}" else ""
        val partyPart = if (record.party.isNotBlank()) " (${record.party})" else ""
        "$who recorded $kind $amtStr$catPart$partyPart"
      }
      return RemoteActivityAlert(
        title = "New ledger entry",
        body = body,
        targetKey = targetKey,
        targetId = targetId,
        tag = tag,
      )
    }

    val who = record.author.trim()
    if (who.isBlank()) return null

    return when (key) {
      "sales" -> RemoteActivityAlert(
        title = "New sale",
        body = "$who added a sale: ${record.customerName.ifBlank { "customer" }} — ${formatMoney(record.totalValue)}",
        targetKey = targetKey,
        targetId = targetId,
        tag = tag,
      )
      "transactions" -> {
        if (record.isExpense) null
        else {
          val verb = if (record.type.equals("IN", ignoreCase = true) || record.type.equals("PAYMENT_IN", ignoreCase = true)) {
            "received from"
          } else {
            "paid to"
          }
          RemoteActivityAlert(
            title = "New payment",
            body = "$who recorded a payment $verb ${record.entityName.ifBlank { "entity" }} — ${formatMoney(record.amount)}",
            targetKey = targetKey,
            targetId = targetId,
            tag = tag,
          )
        }
      }
      "expenses" -> RemoteActivityAlert(
        title = "New expense",
        body = "$who added an expense: ${record.name.ifBlank { record.description }.ifBlank { "expense" }} — ${formatMoney(record.amount)}",
        targetKey = targetKey,
        targetId = targetId,
        tag = tag,
      )
      "production" -> {
        val kg = kotlin.math.abs(record.netKg)
        val kgFmt = SarimFormat.formatQty(kg, 2)
        when {
          record.isTransfer -> {
            if (record.transferDirection.equals("out", ignoreCase = true)) {
              RemoteActivityAlert(
                title = "Stock transfer",
                body = "$who transferred $kgFmt kg",
                targetKey = targetKey,
                targetId = targetId,
                tag = tag,
              )
            } else {
              null
            }
          }
          record.isReturn -> RemoteActivityAlert(
            title = "Stock return",
            body = "$who returned $kgFmt kg",
            targetKey = targetKey,
            targetId = targetId,
            tag = tag,
          )
          else -> RemoteActivityAlert(
            title = "New production",
            body = "$who recorded $kgFmt kg produced",
            targetKey = targetKey,
            targetId = targetId,
            tag = tag,
          )
        }
      }
      "calculator" -> RemoteActivityAlert(
        title = "New calculator entry",
        body = "$who saved a calculator entry${if (record.seller.isNotBlank()) " for ${record.seller}" else ""} — ${formatMoney(record.revenue)}",
        targetKey = targetKey,
        targetId = targetId,
        tag = tag,
      )
      "factory" -> {
        val unitsStr = if (record.units > 0.0) " — ${SarimFormat.formatQty(record.units, 1)} units" else ""
        val fStr = if (record.formulaName.isNotBlank()) " (${record.formulaName})" else ""
        RemoteActivityAlert(
          title = "New factory entry",
          body = "$who added a factory entry$unitsStr$fStr",
          targetKey = targetKey,
          targetId = targetId,
          tag = tag,
        )
      }
      else -> null
    }
  }

  fun planNotifications(rawAlerts: List<RemoteActivityAlert>): List<RemoteActivityAlert> {
    if (rawAlerts.isEmpty()) return emptyList()
    val shown = rawAlerts.take(REMOTE_MAX_NOTIFICATIONS).toMutableList()
    if (rawAlerts.size > REMOTE_MAX_NOTIFICATIONS) {
      val extra = rawAlerts.size - REMOTE_MAX_NOTIFICATIONS
      shown += RemoteActivityAlert(
        title = "More new activity",
        body = "$extra more records arrived from your team. Open the app to see them all.",
        targetKey = "",
        targetId = "",
        tag = "tx-more-remote",
      )
    }
    return shown
  }

  fun diffSnapshots(
    beforeKnownIdsByCollection: Map<String, Set<String>>,
    afterRecordsByCollection: Map<String, List<RemoteActivityRecord>>,
    isOwnRecord: (String) -> Boolean,
    formatMoney: (Double) -> String = { SarimFormat.formatRs(it) },
    nowMs: Long = System.currentTimeMillis(),
  ): List<RemoteActivityAlert> {
    val discovered = mutableListOf<RemoteActivityAlert>()
    for (col in REMOTE_KEYS) {
      val known = beforeKnownIdsByCollection[col] ?: continue
      if (known.isEmpty()) continue
      val incoming = afterRecordsByCollection[col].orEmpty()
      for (rec in incoming) {
        if (rec.id in known) continue
        if (isOwnRecord(rec.id)) continue
        describeRemote(rec, formatMoney, nowMs)?.let { discovered += it }
      }
    }
    return planNotifications(discovered)
  }

  fun mapRawDoc(collection: String, docId: String, m: Map<String, Any?>): RemoteActivityRecord {
    val managed = m["managedBy"]?.toString()?.trim().orEmpty()
    val createdBy = m["createdBy"]?.toString()?.trim().orEmpty()
    val author = managed.ifBlank { createdBy }
    val createdAt = SarimWebSchema.parseEpoch(m["createdAt"], null)
    val timestamp = SarimWebSchema.parseEpoch(m["timestamp"], null)
    return RemoteActivityRecord(
      id = docId,
      collection = collection,
      createdAt = createdAt,
      timestamp = timestamp,
      isMerged = SarimWebSchema.bool(m["isMerged"]),
      isDeleted = SarimWebSchema.bool(m["deleted"]) || SarimWebSchema.bool(m["isDeleted"]),
      author = author,
      salesRep = m["salesRep"]?.toString()?.trim().orEmpty(),
      isCollection = SarimWebSchema.bool(m["isCollection"]),
      isTransfer = SarimWebSchema.bool(m["isTransfer"]),
      transferDirection = m["transferDirection"]?.toString()?.trim().orEmpty(),
      isReturn = SarimWebSchema.bool(m["isReturn"]),
      isExpense = SarimWebSchema.bool(m["isExpense"]),
      customerName = m["customerName"]?.toString()?.trim().orEmpty(),
      name = m["name"]?.toString()?.trim().orEmpty(),
      entityName = m["entityName"]?.toString()?.trim().orEmpty(),
      description = m["description"]?.toString()?.trim().orEmpty(),
      category = m["category"]?.toString()?.trim().orEmpty(),
      party = m["party"]?.toString()?.trim().orEmpty(),
      formulaName = m["formulaName"]?.toString()?.trim().orEmpty(),
      type = m["type"]?.toString()?.trim().orEmpty(),
      mode = m["mode"]?.toString()?.trim().orEmpty(),
      toMode = m["toMode"]?.toString()?.trim().orEmpty(),
      seller = m["seller"]?.toString()?.trim().orEmpty(),
      amount = SarimWebSchema.num(m["amount"]),
      totalValue = SarimWebSchema.num(m["totalValue"]),
      netKg = SarimWebSchema.num(m["net"]),
      units = SarimWebSchema.num(m["units"]),
      revenue = SarimWebSchema.num(m["revenue"]),
    )
  }

  fun mapLedgerDocEntries(doc: Map<String, Any?>): List<RemoteActivityRecord> {
    @Suppress("UNCHECKED_CAST")
    val entries = doc["entries"] as? List<Map<String, Any?>> ?: return emptyList()
    return entries.mapNotNull { m ->
      val id = m["id"]?.toString()?.trim().orEmpty()
      if (id.isEmpty()) return@mapNotNull null
      val created = SarimWebSchema.parseEpoch(m["timestamp"] ?: m["createdAt"], null)
      val author = m["managedBy"]?.toString()?.trim().orEmpty()
        .ifBlank { m["createdBy"]?.toString()?.trim().orEmpty() }
      RemoteActivityRecord(
        id = id,
        collection = "daily_ledger",
        createdAt = created,
        timestamp = created,
        isMerged = SarimWebSchema.bool(m["isMerged"]),
        isDeleted = SarimWebSchema.bool(m["deleted"]),
        author = author,
        type = m["type"]?.toString()?.trim().orEmpty(),
        mode = m["mode"]?.toString()?.trim().orEmpty(),
        toMode = m["toMode"]?.toString()?.trim().orEmpty(),
        category = m["category"]?.toString()?.trim().orEmpty(),
        party = m["party"]?.toString()?.trim().orEmpty(),
        amount = SarimWebSchema.num(m["amount"]),
      )
    }
  }

  suspend fun captureKnownIdsSnapshot(dao: SarimDao): Map<String, Set<String>> {
    val salesAll = dao.getAllSales()
    val custAll = dao.getAllCustomers()
    val prodIds = dao.getAllProductionEntries().map { it.id }.toSet() +
      dao.getAllStockTransfers().flatMap { listOf(it.id, it.outDocId, it.inDocId) }.filter { it.isNotBlank() }
    val txIds = dao.getAllPaymentTransactions().map { it.id }.toSet() +
      dao.getAllPaymentTransfers().flatMap { listOf(it.id, it.outDocId, it.inDocId) }.filter { it.isNotBlank() }
    return mapOf(
      "sales" to salesAll.filter { !it.isRepSale }.map { it.id }.toSet(),
      "rep" to salesAll.filter { it.isRepSale }.map { it.id }.toSet(),
      "clients" to custAll.filter { it.isRepCustomer }.map { it.id }.toSet(),
      "transactions" to txIds,
      "expenses" to dao.getAllExpenses().map { it.id }.toSet(),
      "production" to prodIds,
      "factory" to dao.getAllFactoryBatches().map { it.id }.toSet(),
      "calculator" to dao.getAllSettlements().map { it.id }.toSet(),
      "daily_ledger" to dao.getAllDailyLedger().map(DailyLedgerEntity::id).toSet(),
    )
  }
}
