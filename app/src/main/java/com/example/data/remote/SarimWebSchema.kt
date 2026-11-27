package com.example.data.remote

import android.util.Base64
import com.example.data.local.*
import com.example.data.model.EditHistoryEntry
import com.example.data.model.FormulaIngredient
import com.example.util.SarimFormat
import com.google.firebase.Timestamp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exact Firestore collection paths, singleton document paths, SQLite/KV key names,
 * and bidirectional serialization contracts cloned from the Sarim web repository.
 */
object SarimWebSchema {
  const val COL_PRODUCTION = "production"
  const val COL_SALES = "sales"
  const val COL_REP = "rep"
  const val COL_CLIENTS = "clients"
  const val COL_CUSTOMERS = "customers"
  const val COL_TRANSACTIONS = "transactions"
  const val COL_ENTITIES = "entities"
  const val COL_INVENTORY = "inventory"
  const val COL_FACTORY = "factory"
  const val COL_RETURNS = "returns"
  const val COL_EXPENSES = "expenses"
  const val COL_CALCULATOR = "calculator"
  const val COL_PENDING = "pending"
  const val COL_DELETIONS = "deletions"
  const val COL_PHOTOS = "photos"
  const val COL_STORES = "stores"
  const val COL_FORMULAS = "formulas"
  const val COL_DAILY_LEDGER = "daily_ledger"
  const val COL_CATEGORIES = "categories"
  const val COL_FIELD_EXP_CATS = "field_expense_categories"
  const val COL_COMM_CATS = "commission_categories"
  const val COL_SETTINGS = "settings"

  val RECORD_COLLECTIONS = listOf(
    COL_PRODUCTION, COL_SALES, COL_CALCULATOR, COL_REP,
    COL_CLIENTS, COL_CUSTOMERS, COL_TRANSACTIONS, COL_ENTITIES,
    COL_INVENTORY, COL_FACTORY, COL_RETURNS, COL_EXPENSES,
  )

  val GATED_COLLECTIONS = setOf(
    COL_PRODUCTION, COL_SALES, COL_REP, COL_CLIENTS, COL_CUSTOMERS, COL_CALCULATOR,
    COL_INVENTORY, COL_FACTORY, COL_ENTITIES, COL_TRANSACTIONS, COL_EXPENSES, COL_RETURNS,
  )

  fun isGatedCollection(col: String): Boolean = col in GATED_COLLECTIONS

  fun pendingId(collection: String, docId: String): String =
    "${collection}__${docId.replace("/", "_")}"

  private val PENDING_LABELS = mapOf(
    COL_REP to "Sale",
    COL_SALES to "Sale",
    COL_TRANSACTIONS to "Payment",
    COL_EXPENSES to "Expense",
    COL_PRODUCTION to "Production entry",
    COL_FACTORY to "Factory entry",
    COL_CLIENTS to "Customer",
    COL_CUSTOMERS to "Customer",
    COL_INVENTORY to "Raw material",
    COL_ENTITIES to "Entity",
    COL_RETURNS to "Return",
    COL_CALCULATOR to "Calculation",
  )

  fun labelForPending(collection: String): String = PENDING_LABELS[collection] ?: "Record"

  fun categoryForPending(collection: String): String = when (collection.lowercase()) {
    COL_SALES, COL_REP, COL_CALCULATOR -> "Sales"
    COL_TRANSACTIONS, COL_ENTITIES -> "Payments"
    COL_EXPENSES -> "Expenses"
    COL_PRODUCTION -> "Production"
    COL_FACTORY -> "Factory"
    COL_INVENTORY -> "Stock"
    COL_CUSTOMERS, COL_CLIENTS -> "Customers"
    else -> "Other"
  }

  private fun pendingAmountText(d: Map<String, Any?>): String {
    val amtRaw = d["totalValue"] ?: d["amount"]
    if (amtRaw != null) {
      val v = amtRaw.toString().toDoubleOrNull()
      if (v != null && v.isFinite()) return SarimFormat.formatRs(v)
    }
    val netRaw = d["net"]
    if (netRaw != null) {
      val v = netRaw.toString().toDoubleOrNull()
      if (v != null && v.isFinite()) return "${kotlin.math.abs(v)} kg"
    }
    val unitsRaw = d["units"]
    if (unitsRaw != null) {
      val v = unitsRaw.toString().toDoubleOrNull()
      if (v != null && v != 0.0 && v.isFinite()) return "$v units"
    }
    return ""
  }

  fun summarizePending(
    collection: String,
    docId: String,
    action: String,
    isEdit: Boolean,
    data: Map<String, Any?>?,
  ): Map<String, String> {
    val label = labelForPending(collection)
    if (action == "delete") {
      return linkedMapOf(
        "title" to "Delete ${label.lowercase()}",
        "body" to "Remove ${label.lowercase()} ${docId.take(8)}",
      )
    }
    val d = data.orEmpty()
    val who = listOf("customerName", "entityName", "name", "description", "formulaName", "seller", "store")
      .firstNotNullOfOrNull { k -> d[k]?.toString()?.trim()?.takeIf { it.isNotEmpty() } }
      .orEmpty()
    val amtText = pendingAmountText(d)
    val parts = listOf(who, amtText).filter { it.isNotBlank() }
    return linkedMapOf(
      "title" to "${if (isEdit) "Change" else "New"} ${label.lowercase()}",
      "body" to (parts.joinToString(" — ").ifBlank { label }),
    )
  }

  fun buildPendingDocMap(
    collection: String,
    docId: String,
    action: String,
    data: Map<String, Any?>?,
    whoUid: String,
    whoName: String,
    whoEmail: String,
    nowMs: Long = System.currentTimeMillis(),
    isEdit: Boolean = false,
  ): Map<String, Any?> {
    val cleanAction = if (action == "delete") "delete" else "set"
    val cleanData = if (cleanAction == "delete") null else data?.filterValues { it != null }
    val pid = pendingId(collection, docId)
    val summary = summarizePending(collection, docId, cleanAction, isEdit, cleanData)
    val doc = linkedMapOf<String, Any?>(
      "id" to pid,
      "collection" to collection,
      "docId" to docId,
      "action" to cleanAction,
      "data" to cleanData,
      "isEdit" to isEdit,
      "status" to "pending",
      "submittedBy" to linkedMapOf(
        "uid" to whoUid,
        "name" to whoName,
        "email" to whoEmail,
      ),
      "submittedAt" to nowMs,
      "summary" to summary,
    )
    return doc
  }

  fun settingsConfigDocPayload(
    fyCloseCount: Int,
    lastYearClosedAt: Long?,
    lastYearClosedDate: String?,
    settingsTs: Long,
    reps: List<String>,
    repsTs: Long,
    repProfile: String? = null,
    now: Long = System.currentTimeMillis(),
  ): Map<String, Any?> {
    val settingsObj = linkedMapOf<String, Any?>(
      "fyCloseCount" to fyCloseCount,
      "lastYearClosedAt" to lastYearClosedAt,
      "lastYearClosedDate" to lastYearClosedDate,
    ).filterValues { it != null }
    return linkedMapOf(
      "settings" to settingsObj,
      "settings_timestamp" to settingsTs,
      "reps" to reps,
      "reps_timestamp" to repsTs,
      "repProfile" to (repProfile ?: reps.firstOrNull() ?: ""),
      "repProfile_timestamp" to now,
      "synced" to isoUtc(now),
    )
  }

  fun teamDocPayload(reps: List<String>, now: Long = System.currentTimeMillis()): Map<String, Any?> = linkedMapOf(
    "reps" to reps,
    "updated" to now,
    "synced" to isoUtc(now),
  )

  fun yearCloseSignalPayload(
    type: String,
    triggeredAt: Long,
    triggeredBy: String,
    fyCloseCount: Int,
  ): Map<String, Any?> = linkedMapOf(
    "type" to type,
    "triggeredAt" to triggeredAt,
    "triggeredBy" to triggeredBy,
    "fyCloseCount" to fyCloseCount,
  )

  const val TOMBSTONE_EXPIRY_MS = 90L * 24L * 60L * 60L * 1000L

  fun isoUtc(ms: Long = System.currentTimeMillis()): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    return sdf.format(Date(ms))
  }

  fun parseEpoch(value: Any?, fallbackDateStr: String? = null): Long {
    when (value) {
      is Number -> return value.toLong()
      is Timestamp -> return value.toDate().time
      is Date -> return value.time
      is String -> {
        value.toLongOrNull()?.let { return it }
        runCatching {
          SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).parse(value)?.time
        }.getOrNull()?.let { return it }
        runCatching {
          SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).parse(value)?.time
        }.getOrNull()?.let { return it }
      }
    }
    if (!fallbackDateStr.isNullOrBlank()) {
      runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(fallbackDateStr)?.time
      }.getOrNull()?.let { return it }
    }
    return System.currentTimeMillis()
  }

  fun num(value: Any?, default: Double = 0.0): Double = when (value) {
    is Number -> value.toDouble().takeIf { it.isFinite() } ?: default
    is String -> value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: default
    else -> default
  }

  fun str(value: Any?, default: String = ""): String = value?.toString() ?: default

  fun bool(value: Any?, default: Boolean = false): Boolean = when (value) {
    is Boolean -> value
    is String -> value.equals("true", ignoreCase = true)
    is Number -> value.toInt() != 0
    else -> default
  }

  /** Matches web `btoa(unescape(encodeURIComponent(key))).replace(/[+/=]/g, ...)` */
  fun photoDocId(photoKey: String): String =
    Base64.encodeToString(
      photoKey.toByteArray(Charsets.UTF_8),
      Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

  fun editHistoryToMaps(rawJson: String): List<Map<String, Any?>> =
    JsonCodecs.decodeEditHistory(rawJson).map {
      linkedMapOf("timestamp" to it.timestamp, "summary" to it.summary)
    }

  fun mapEditHistoryFromWeb(raw: Any?): String {
    val arr = raw as? List<*> ?: return "[]"
    val parsed = arr.mapNotNull { entry ->
      val m = entry as? Map<*, *> ?: return@mapNotNull null
      val summary = str(m["summary"]).ifBlank { str(m["note"]) }
      if (summary.isBlank()) return@mapNotNull null
      EditHistoryEntry(timestamp = parseEpoch(m["timestamp"]), summary = summary)
    }
    return JsonCodecs.encodeEditHistory(parsed)
  }

  fun categoriesDocPayload(colKey: String, categories: List<String>, ts: Long): Map<String, Any?> = linkedMapOf(
    colKey to categories,
    "${colKey}_timestamp" to ts,
    "synced" to isoUtc(ts),
  )

  // ---------- PRODUCTION (users/{uid}/production/{id}) ----------

  fun productionToMap(e: ProductionEntryEntity): Map<String, Any?> {
    val formulaMats = JsonCodecs.decodeFormulaMaterialsUsed(e.formulaMaterialsJson).map {
      linkedMapOf<String, Any?>(
        "id" to it.id,
        "name" to it.name,
        "quantity" to it.quantity,
        "cost" to it.cost,
      )
    }
    val out = linkedMapOf<String, Any?>(
      "id" to e.id,
      "date" to e.dateStr.ifBlank { SarimFormat.dateInput(e.dateEpoch) },
      "time" to e.timeStr.ifBlank { SarimFormat.formatTime(e.dateEpoch) },
      "store" to e.storeKey,
      "storeName" to e.storeName,
      "net" to e.netWeightKg,
      "cp" to e.costPerKg,
      "sp" to e.salePricePerKg,
      "totalCost" to e.totalFormulaCost,
      "totalSale" to e.totalValue,
      "profit" to e.totalProfit,
      "formulaUnits" to e.formulaUnitsUsed,
      "formulaId" to e.formulaId,
      "formulaName" to e.formulaName,
      "formulaCost" to e.totalFormulaCost,
      "formulaMaterials" to formulaMats,
      "grossWt" to e.grossWeightKg,
      "contWt" to e.containerWeightKg,
      "paymentStatus" to e.paymentStatus,
      "photoKeys" to JsonCodecs.decodeStrings(e.photoUrisJson),
      "editHistory" to editHistoryToMaps(e.editHistoryJson),
      "createdBy" to e.createdBy,
      "recordDate" to e.dateEpoch,
      "timestamp" to e.createdAt,
      "createdAt" to e.createdAt,
      "updatedAt" to e.updatedAt,
      "syncedAt" to isoUtc(e.updatedAt),
    )
    if (e.isReturn) {
      out["isReturn"] = true
      out["returnedBy"] = e.returnedBy
      out["returnNote"] = e.returnNote
      out["returnLogId"] = e.returnLogId
    }
    return out
  }

  fun mapToProduction(id: String, m: Map<String, Any?>): ProductionEntryEntity? {
    if (bool(m["_placeholder"]) || bool(m["isTransfer"])) return null
    val dateStr = str(m["date"], SarimFormat.dateInput(System.currentTimeMillis()))
    val epoch = parseEpoch(m["recordDate"] ?: m["timestamp"], dateStr)
    val net = num(m["net"])
    val gross = num(m["grossWt"], net)
    val cont = num(m["contWt"])
    val cp = num(m["cp"])
    val sp = num(m["sp"])
    val units = num(m["formulaUnits"])
    val fCost = num(m["formulaCost"], num(m["totalCost"]))
    val totalSale = num(m["totalSale"], net * sp)
    val profit = num(m["profit"], totalSale - fCost)
    val photosRaw = (m["photoKeys"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
    val fMats = (m["formulaMaterials"] as? List<*>)?.mapNotNull { item ->
      val obj = item as? Map<*, *> ?: return@mapNotNull null
      JsonCodecs.FormulaMaterialUsed(
        id = str(obj["id"]),
        name = str(obj["name"]),
        quantity = num(obj["quantity"]),
        cost = num(obj["cost"]),
      )
    }.orEmpty()
    return ProductionEntryEntity(
      id = id,
      dateEpoch = epoch,
      storeKey = str(m["store"]),
      storeName = str(m["storeName"], str(m["store"]).uppercase()),
      formulaId = str(m["formulaId"]),
      formulaName = str(m["formulaName"], "Formula"),
      grossWeightKg = gross,
      containerWeightKg = cont,
      netWeightKg = net,
      formulaUnitsUsed = units,
      formulaUnitCost = if (units > 0.0001) fCost / units else 0.0,
      totalFormulaCost = fCost,
      costPerKg = cp,
      salePricePerKg = sp,
      profitPerKg = sp - cp,
      totalValue = totalSale,
      totalProfit = profit,
      photoUrisJson = JsonCodecs.encodeStrings(photosRaw),
      dateStr = dateStr,
      timeStr = str(m["time"]),
      paymentStatus = str(m["paymentStatus"], "CASH"),
      formulaMaterialsJson = JsonCodecs.encodeFormulaMaterialsUsed(fMats),
      isReturn = bool(m["isReturn"]),
      returnedBy = m["returnedBy"]?.toString(),
      returnNote = m["returnNote"]?.toString(),
      returnLogId = m["returnLogId"]?.toString(),
      editHistoryJson = mapEditHistoryFromWeb(m["editHistory"]),
      createdBy = m["createdBy"]?.toString(),
      syncPending = false,
      createdAt = parseEpoch(m["createdAt"] ?: epoch),
      updatedAt = parseEpoch(m["updatedAt"] ?: epoch),
    )
  }

  fun stockTransferToOutInMaps(e: StockTransferEntity): Pair<Pair<String, Map<String, Any?>>, Pair<String, Map<String, Any?>>> {
    val date = e.dateStr.ifBlank { SarimFormat.dateInput(e.dateEpoch) }
    val outMap = linkedMapOf<String, Any?>(
      "id" to e.outDocId,
      "date" to date,
      "store" to e.fromStoreKey,
      "net" to e.quantityKg,
      "isTransfer" to true,
      "transferDirection" to "out",
      "transferPairId" to e.id,
      "transferPeerStore" to e.toStoreKey,
      "transferNote" to e.note,
      "timestamp" to e.dateEpoch,
      "createdAt" to e.createdAt,
      "updatedAt" to e.updatedAt,
      "syncedAt" to isoUtc(e.updatedAt),
    )
    val inMap = linkedMapOf<String, Any?>(
      "id" to e.inDocId,
      "date" to date,
      "store" to e.toStoreKey,
      "net" to e.quantityKg,
      "isTransfer" to true,
      "transferDirection" to "in",
      "transferPairId" to e.id,
      "transferPeerStore" to e.fromStoreKey,
      "transferNote" to e.note,
      "timestamp" to e.dateEpoch,
      "createdAt" to e.createdAt,
      "updatedAt" to e.updatedAt,
      "syncedAt" to isoUtc(e.updatedAt),
    )
    return (e.outDocId to outMap) to (e.inDocId to inMap)
  }

  // ---------- SALES & REP (users/{uid}/sales/{id} & users/{uid}/rep/{id}) ----------

  fun saleToMap(e: SaleTransactionEntity): Map<String, Any?> {
    val isOldDebt = e.transactionType == "OLD_DEBT" || e.mode == "OLD_DEBT"
    val webPayType = when {
      isOldDebt -> "CREDIT"
      e.paymentType == "REP_RETURN" || e.isRepReturn -> "REP_RETURN"
      e.mode == "COLLECTION" -> if (!e.relatedSaleId.isNullOrBlank()) "PARTIAL_PAYMENT" else "COLLECTION"
      e.mode == "MONEY_OUT" || e.isMoneyOut -> "MONEY_OUT"
      else -> e.paymentType
    }
    val allocs = JsonCodecs.decodeAllocations(e.allocationsJson).map {
      linkedMapOf<String, Any?>("saleId" to it.saleId, "amount" to it.amount, "full" to it.full)
    }
    val collAllocs = JsonCodecs.decodeCollectionAllocs(e.collectionAllocsJson).map {
      linkedMapOf<String, Any?>("cid" to it.cid, "amount" to it.amount, "full" to it.full)
    }
    val out = linkedMapOf<String, Any?>(
      "id" to e.id,
      "date" to e.dateStr.ifBlank { SarimFormat.dateInput(e.dateEpoch) },
      "supplyDate" to e.dateStr.ifBlank { SarimFormat.dateInput(e.dateEpoch) },
      "time" to e.timeStr.ifBlank { SarimFormat.formatTime(e.dateEpoch) },
      "customerName" to e.customerName,
      "customerPhone" to e.customerPhone,
      "quantity" to e.quantityKg,
      "supplyStore" to e.storeKey.ifBlank { null },
      "paymentType" to webPayType,
      "salesRep" to if (e.isRepSale) e.repName else if (isOldDebt) "ADMIN" else "NONE",
      "currentRepProfile" to if (e.isRepSale) e.repName else "admin",
      "totalCost" to e.totalCost,
      "costSource" to e.costSource,
      "totalValue" to e.totalValue,
      "profit" to e.profit,
      "unitPrice" to e.pricePerKg,
      "creditReceived" to (e.creditReceived || (!isOldDebt && (webPayType == "CASH" || webPayType == "COLLECTION" || webPayType == "PARTIAL_PAYMENT"))),
      "isCollection" to (e.mode == "COLLECTION" || e.isCollection),
      "expiredQty" to e.expiredQtyKg,
      "expiredValue" to e.expiredValue.takeIf { it > 0.0 },
      "expiredUnitPrice" to e.expiredUnitPrice.takeIf { it > 0.0 },
      "expiredAllocated" to e.expiredAllocated.takeIf { it > 0.0 },
      "returnedQty" to e.returnedQtyKg,
      "affectsInventory" to e.affectsInventory,
      "isMoneyOut" to (e.isMoneyOut || webPayType == "MONEY_OUT"),
      "isRepReturn" to (e.isRepReturn || webPayType == "REP_RETURN"),
      "notes" to e.notes.ifBlank { null },
      "transactionType" to e.transactionType,
      "usedInCalcId" to e.usedInCalcId,
      "relatedSaleId" to e.relatedSaleId,
      "ledgerId" to e.ledgerId,
      "collectedAmount" to e.collectedAmount.takeIf { it > 0.0 },
      "allocations" to allocs.takeIf { it.isNotEmpty() },
      "partialPaymentReceived" to e.partialPaymentReceived.takeIf { it > 0.0 || isOldDebt },
      "collectionAllocs" to collAllocs.takeIf { it.isNotEmpty() },
      "editHistory" to editHistoryToMaps(e.editHistoryJson),
      "createdBy" to e.createdBy,
      "gps" to if (e.latitude != null && e.longitude != null) mapOf("lat" to e.latitude, "lng" to e.longitude) else null,
      "timestamp" to e.dateEpoch,
      "createdAt" to e.createdAt,
      "updatedAt" to e.updatedAt,
      "syncedAt" to isoUtc(e.updatedAt),
    )
    return out
  }

  fun mapToSale(id: String, m: Map<String, Any?>, isRep: Boolean): SaleTransactionEntity? {
    if (bool(m["_placeholder"])) return null
    val dateStr = str(m["date"], SarimFormat.dateInput(System.currentTimeMillis()))
    val epoch = parseEpoch(m["timestamp"] ?: m["createdAt"], dateStr)
    val payType = str(m["paymentType"], "CREDIT")
    val txType = m["transactionType"]?.toString()?.takeIf { it.isNotBlank() }
    val isColl = bool(m["isCollection"]) || payType == "COLLECTION" || payType == "PARTIAL_PAYMENT"
    val isMoneyOut = bool(m["isMoneyOut"]) || payType == "MONEY_OUT"
    val isRepReturn = bool(m["isRepReturn"]) || payType == "REP_RETURN"
    val mode = when {
      txType == "OLD_DEBT" -> "OLD_DEBT"
      isMoneyOut -> "MONEY_OUT"
      isColl -> "COLLECTION"
      else -> "SALE"
    }
    @Suppress("UNCHECKED_CAST")
    val gps = m["gps"] as? Map<String, Any?>
    val qty = num(m["quantity"])
    val totalVal = num(m["totalValue"])
    val collAmt = num(m["collectedAmount"], if (mode == "COLLECTION" || mode == "MONEY_OUT") totalVal else 0.0)
    val allocs = (m["allocations"] as? List<*>)?.mapNotNull { item ->
      val o = item as? Map<*, *> ?: return@mapNotNull null
      JsonCodecs.AllocationItem(str(o["saleId"]), num(o["amount"]), bool(o["full"]))
    }.orEmpty()
    val collAllocs = (m["collectionAllocs"] as? List<*>)?.mapNotNull { item ->
      val o = item as? Map<*, *> ?: return@mapNotNull null
      JsonCodecs.CollectionAllocItem(str(o["cid"]), num(o["amount"]), bool(o["full"]))
    }.orEmpty()
    return SaleTransactionEntity(
      id = id,
      dateEpoch = epoch,
      mode = mode,
      customerId = "",
      customerName = str(m["customerName"]),
      repName = str(m["salesRep"]).takeIf { it.isNotBlank() && !it.equals("NONE", true) && !it.equals("ADMIN", true) } ?: "Direct",
      storeKey = str(m["supplyStore"]),
      storeName = str(m["supplyStore"]).uppercase(),
      quantityKg = qty,
      pricePerKg = num(m["unitPrice"], if (qty > 0) totalVal / qty else 0.0),
      totalValue = totalVal,
      totalCost = num(m["totalCost"]),
      profit = num(m["profit"]),
      paymentType = if (payType == "CASH") "CASH" else if (isRepReturn) "REP_RETURN" else "CREDIT",
      amountReceived = if (mode == "COLLECTION") collAmt else if (mode == "MONEY_OUT") totalVal else if (payType == "CASH") totalVal else 0.0,
      expiredQtyKg = num(m["expiredQty"]),
      returnedQtyKg = num(m["returnedQty"]),
      addToLedger = !m["ledgerId"]?.toString().isNullOrBlank(),
      isRepSale = isRep,
      latitude = gps?.get("lat")?.let { num(it) },
      longitude = gps?.get("lng")?.let { num(it) },
      dateStr = dateStr,
      timeStr = str(m["time"]),
      customerPhone = str(m["customerPhone"]),
      creditReceived = bool(m["creditReceived"]),
      isCollection = isColl,
      usedInCalcId = m["usedInCalcId"]?.toString(),
      relatedSaleId = m["relatedSaleId"]?.toString(),
      ledgerId = m["ledgerId"]?.toString(),
      notes = str(m["notes"]),
      transactionType = txType,
      costSource = m["costSource"]?.toString(),
      affectsInventory = if (m.containsKey("affectsInventory")) bool(m["affectsInventory"], true) else !isRep,
      isMoneyOut = isMoneyOut,
      isRepReturn = isRepReturn,
      expiredValue = num(m["expiredValue"]),
      expiredUnitPrice = num(m["expiredUnitPrice"]),
      expiredAllocated = num(m["expiredAllocated"]),
      collectedAmount = collAmt,
      allocationsJson = JsonCodecs.encodeAllocations(allocs),
      partialPaymentReceived = num(m["partialPaymentReceived"]),
      collectionAllocsJson = JsonCodecs.encodeCollectionAllocs(collAllocs),
      editHistoryJson = mapEditHistoryFromWeb(m["editHistory"]),
      createdBy = m["createdBy"]?.toString(),
      syncPending = false,
      createdAt = parseEpoch(m["createdAt"] ?: epoch),
      updatedAt = parseEpoch(m["updatedAt"] ?: epoch),
    )
  }

  // ---------- CUSTOMERS & CLIENTS (customers/{id} & clients/{id}) ----------

  fun customerToMap(e: CustomerEntity): Map<String, Any?> = linkedMapOf(
    "id" to e.id,
    "name" to e.name,
    "phone" to e.phone,
    "address" to e.address,
    "oldDebit" to e.oldDebit,
    "customSalePrice" to (e.customSalePrice ?: 0.0),
    "ledgerPage" to e.ledgerPage,
    "salesRep" to if (e.isRepCustomer) e.repName else null,
    "currentCredit" to e.currentCredit,
    "totalQuantityKg" to e.totalQuantityKg,
    "totalSaleValue" to e.totalSaleValue,
    "totalReceived" to e.totalReceived,
    "editHistory" to editHistoryToMaps(e.editHistoryJson),
    "createdAt" to e.createdAt,
    "updatedAt" to e.updatedAt,
    "timestamp" to e.createdAt,
    "syncedAt" to isoUtc(e.updatedAt),
  )

  fun mapToCustomer(id: String, m: Map<String, Any?>, isRepCustomer: Boolean): CustomerEntity? {
    if (bool(m["_placeholder"])) return null
    val customPrice = num(m["customSalePrice"]).takeIf { it > 0.0 }
    val oldDebit = num(m["oldDebit"])
    return CustomerEntity(
      id = id,
      name = str(m["name"]),
      phone = str(m["phone"]),
      address = str(m["address"]),
      oldDebit = oldDebit,
      customSalePrice = customPrice,
      ledgerPage = str(m["ledgerPage"]),
      isRepCustomer = isRepCustomer,
      repName = str(m["salesRep"]),
      currentCredit = num(m["currentCredit"], oldDebit),
      totalQuantityKg = num(m["totalQuantityKg"]),
      totalSaleValue = num(m["totalSaleValue"]),
      totalReceived = num(m["totalReceived"]),
      editHistoryJson = mapEditHistoryFromWeb(m["editHistory"]),
      syncPending = false,
      createdAt = parseEpoch(m["createdAt"] ?: m["timestamp"]),
      updatedAt = parseEpoch(m["updatedAt"] ?: m["timestamp"]),
    )
  }

  // ---------- ENTITIES & TRANSACTIONS & EXPENSES ----------

  fun entityToMap(e: PaymentEntityRecord): Map<String, Any?> {
    val signedOpening = if (e.openingType == "DEBIT") -kotlin.math.abs(e.openingAmount) else kotlin.math.abs(e.openingAmount)
    return linkedMapOf(
      "id" to e.id,
      "name" to e.name,
      "type" to (e.type.ifBlank { if (e.openingType == "DEBIT") "payor" else "payee" }),
      "phone" to e.phone,
      "wallet" to e.wallet,
      "address" to e.address,
      "openingBalance" to signedOpening,
      "currentBalance" to e.currentBalance,
      "ledgerPage" to e.ledgerPage,
      "isSupplier" to e.isSupplier,
      "isExpenseEntity" to e.isExpenseEntity,
      "category" to e.category.ifBlank { null },
      "notes" to e.notes,
      "editHistory" to editHistoryToMaps(e.editHistoryJson),
      "createdAt" to e.createdAt,
      "updatedAt" to e.updatedAt,
      "syncedAt" to isoUtc(e.updatedAt),
    )
  }

  fun mapToEntity(id: String, m: Map<String, Any?>): PaymentEntityRecord? {
    if (bool(m["_placeholder"])) return null
    val opening = num(m["openingBalance"])
    return PaymentEntityRecord(
      id = id,
      name = str(m["name"]),
      phone = str(m["phone"]),
      wallet = str(m["wallet"]),
      ledgerPage = str(m["ledgerPage"]),
      openingType = if (opening < 0) "DEBIT" else "CREDIT",
      openingAmount = kotlin.math.abs(opening),
      currentBalance = num(m["currentBalance"], opening),
      type = str(m["type"], if (opening < 0) "payor" else "payee"),
      address = str(m["address"]),
      isSupplier = bool(m["isSupplier"]),
      isExpenseEntity = bool(m["isExpenseEntity"]),
      category = str(m["category"]),
      notes = str(m["notes"]),
      editHistoryJson = mapEditHistoryFromWeb(m["editHistory"]),
      syncPending = false,
      createdAt = parseEpoch(m["createdAt"]),
      updatedAt = parseEpoch(m["updatedAt"]),
    )
  }

  fun transactionToMap(e: PaymentTransactionEntity): Map<String, Any?> {
    val matIds = JsonCodecs.decodeStrings(e.materialIdsJson)
    val consumed = JsonCodecs.decodeStrings(e.consumedCreditsJson)
    return linkedMapOf(
      "id" to e.id,
      "entityId" to (e.entityId ?: ""),
      "entityName" to e.entityOrExpenseName,
      "entityType" to e.entityType.ifBlank { null },
      "amount" to e.amount,
      "type" to if (e.type == "PAYMENT_IN") "IN" else "OUT",
      "date" to e.dateStr.ifBlank { SarimFormat.dateInput(e.dateEpoch) },
      "time" to e.timeStr.ifBlank { SarimFormat.formatTime(e.dateEpoch) },
      "description" to e.description,
      "isPayable" to e.isPayable,
      "materialId" to e.materialId,
      "materialIds" to matIds.takeIf { it.isNotEmpty() },
      "consumedCredits" to consumed.takeIf { it.isNotEmpty() },
      "isExpense" to (e.type == "EXPENSE" || e.isExpense),
      "category" to e.category,
      "expenseId" to e.expenseId,
      "supplierCreditAmount" to e.supplierCreditAmount.takeIf { it > 0.0 },
      "ledgerId" to e.ledgerId,
      "editHistory" to editHistoryToMaps(e.editHistoryJson),
      "createdAt" to e.createdAt,
      "updatedAt" to e.updatedAt,
      "timestamp" to e.dateEpoch,
      "syncedAt" to isoUtc(e.updatedAt),
    )
  }

  fun mapToTransaction(id: String, m: Map<String, Any?>): PaymentTransactionEntity? {
    if (bool(m["_placeholder"]) || bool(m["isTransfer"])) return null
    val dateStr = str(m["date"], SarimFormat.dateInput(System.currentTimeMillis()))
    val epoch = parseEpoch(m["timestamp"] ?: m["createdAt"], dateStr)
    val rawType = str(m["type"], "OUT")
    val isExp = bool(m["isExpense"])
    val domainType = when {
      isExp -> "EXPENSE"
      rawType.equals("IN", true) -> "PAYMENT_IN"
      else -> "PAYMENT_OUT"
    }
    val matIds = (m["materialIds"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
    val consumed = (m["consumedCredits"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
    return PaymentTransactionEntity(
      id = id,
      dateEpoch = epoch,
      type = domainType,
      entityOrExpenseName = str(m["entityName"]),
      entityId = m["entityId"]?.toString()?.takeIf { it.isNotBlank() },
      amount = num(m["amount"]),
      description = str(m["description"]),
      dateStr = dateStr,
      timeStr = str(m["time"]),
      isExpense = isExp,
      expenseId = m["expenseId"]?.toString(),
      isPayable = bool(m["isPayable"]),
      ledgerId = m["ledgerId"]?.toString(),
      entityType = str(m["entityType"]),
      materialId = m["materialId"]?.toString(),
      materialIdsJson = JsonCodecs.encodeStrings(matIds),
      consumedCreditsJson = JsonCodecs.encodeStrings(consumed),
      category = m["category"]?.toString(),
      supplierCreditAmount = num(m["supplierCreditAmount"]),
      editHistoryJson = mapEditHistoryFromWeb(m["editHistory"]),
      syncPending = false,
      createdAt = parseEpoch(m["createdAt"] ?: epoch),
      updatedAt = parseEpoch(m["updatedAt"] ?: epoch),
    )
  }

  fun expenseToMap(e: ExpenseRecordEntity): Map<String, Any?> = linkedMapOf(
    "id" to e.id,
    "name" to e.name,
    "amount" to e.amount,
    "date" to e.date,
    "description" to e.description,
    "category" to e.category,
    "ledgerId" to e.ledgerId,
    "createdAt" to e.createdAt,
    "updatedAt" to e.updatedAt,
    "timestamp" to e.dateEpoch,
    "syncedAt" to isoUtc(e.updatedAt),
  )

  fun mapToExpense(id: String, m: Map<String, Any?>): ExpenseRecordEntity? {
    if (bool(m["_placeholder"])) return null
    val dateStr = str(m["date"], SarimFormat.dateInput(System.currentTimeMillis()))
    val epoch = parseEpoch(m["timestamp"] ?: m["createdAt"], dateStr)
    return ExpenseRecordEntity(
      id = id,
      name = str(m["name"]),
      amount = num(m["amount"]),
      date = dateStr,
      dateEpoch = epoch,
      description = str(m["description"]),
      category = str(m["category"], "operating"),
      ledgerId = m["ledgerId"]?.toString(),
      syncPending = false,
      createdAt = parseEpoch(m["createdAt"] ?: epoch),
      updatedAt = parseEpoch(m["updatedAt"] ?: epoch),
    )
  }

  // ---------- INVENTORY & FACTORY & RETURNS ----------

  fun materialToMap(e: RawMaterialEntity): Map<String, Any?> = linkedMapOf(
    "id" to e.id,
    "name" to e.name,
    "quantity" to e.stockKg,
    "unit" to e.unit,
    "cost" to e.avgCostPerKg,
    "totalValue" to (e.stockKg * e.avgCostPerKg),
    "conversionFactor" to e.conversionFactorKg,
    "purchaseUnitName" to e.unitName,
    "collectedKg" to e.collectedKg,
    "paymentStatus" to e.paymentStatus,
    "supplierId" to e.supplierId,
    "supplierName" to e.supplierName,
    "totalPayable" to e.pendingSupplierAmount,
    "purchasesTracked" to e.purchasesTracked,
    "createdAt" to e.createdAt,
    "updatedAt" to e.updatedAt,
    "syncedAt" to isoUtc(e.updatedAt),
  )

  fun purchaseLotToMap(e: RawMaterialPurchaseEntity): Map<String, Any?> = linkedMapOf(
    "id" to e.id,
    "name" to "${e.materialName} · ${e.purchaseDate}",
    "isPurchaseLot" to true,
    "materialRefId" to e.materialId,
    "materialName" to e.materialName,
    "quantity" to 0.0,
    "unit" to "kg",
    "cost" to if (e.weightPerUnitKg > 0.0) e.costPerUnit / e.weightPerUnitKg else e.costPerUnit,
    "totalValue" to e.totalAmount,
    "purchaseKg" to e.totalKg,
    "purchaseQuantity" to e.unitsBought,
    "purchaseUnitName" to e.unitName,
    "conversionFactor" to e.weightPerUnitKg,
    "purchaseCost" to e.costPerUnit,
    "purchaseDate" to e.purchaseDate,
    "paymentStatus" to e.paymentStatus,
    "supplierId" to e.supplierId,
    "supplierName" to e.supplierName,
    "totalPayable" to e.totalPayable,
    "createdAt" to e.createdAt,
    "updatedAt" to e.updatedAt,
    "timestamp" to e.dateEpoch,
    "syncedAt" to isoUtc(e.updatedAt),
  )

  fun factoryBatchToMap(e: FactoryBatchEntity): Map<String, Any?> = linkedMapOf(
    "id" to e.id,
    "date" to e.dateStr.ifBlank { SarimFormat.dateInput(e.dateEpoch) },
    "time" to e.timeStr.ifBlank { SarimFormat.formatTime(e.dateEpoch) },
    "store" to e.store,
    "formulaId" to e.formulaId,
    "formulaName" to e.formulaName,
    "units" to e.unitsProduced,
    "outputKg" to e.outputKg,
    "costPerUnit" to e.costPerUnit,
    "totalCost" to e.totalCost,
    "materialsCost" to e.materialsCost,
    "additionalCost" to e.additionalCost,
    "rawMaterialsUsed" to e.rawUsedKg,
    "materialsUsed" to JsonCodecs.decodeFormulaMaterialsUsed(e.materialsUsedJson).map {
      linkedMapOf<String, Any?>("id" to it.id, "name" to it.name, "quantity" to it.quantity, "cost" to it.cost)
    },
    "createdAt" to e.createdAt,
    "updatedAt" to e.updatedAt,
    "timestamp" to e.dateEpoch,
    "syncedAt" to isoUtc(e.updatedAt),
  )

  fun returnLogToMap(e: ReturnLogEntity): Map<String, Any?> = linkedMapOf(
    "id" to e.id,
    "date" to e.date,
    "time" to e.time,
    "store" to e.store,
    "quantity" to e.quantity,
    "seller" to e.seller,
    "createdAt" to e.createdAt,
    "updatedAt" to e.updatedAt,
    "timestamp" to e.dateEpoch,
    "syncedAt" to isoUtc(e.updatedAt),
  )

  // ---------- CALCULATOR (users/{uid}/calculator/{id}) ----------

  fun settlementToMap(e: CalculatorSettlementEntity): Map<String, Any?> = linkedMapOf(
    "id" to e.id,
    "seller" to e.repName,
    "date" to e.dateStr.ifBlank { SarimFormat.dateInput(e.dateEpoch) },
    "fromDateEpoch" to e.fromDateEpoch,
    "toDateEpoch" to e.toDateEpoch,
    "unitPrice" to if (e.netSoldKg > 0.0) e.grossRevenue / e.netSoldKg else 0.0,
    "costPrice" to if (e.netSoldKg > 0.0) e.totalCost / e.netSoldKg else 0.0,
    "revenue" to e.grossRevenue,
    "profit" to e.netProfit,
    "totalCost" to e.totalCost,
    "totalSold" to e.totalSoldKg,
    "returned" to e.returnedKg,
    "returnStore" to e.returnStoreKey.ifBlank { null },
    "returnRep" to e.returnRep,
    "returnType" to e.returnType,
    "returnEntryId" to e.returnEntryId,
    "returnLogId" to e.returnLogId,
    "transferSaleId" to e.transferSaleId,
    "expired" to e.expiredKg,
    "expiredApplied" to e.expiredApplied,
    "shared" to e.sharedDeductionKg,
    "creditQty" to e.creditSalesQty,
    "cashQty" to (e.netSoldKg - e.creditSalesQty).coerceAtLeast(0.0),
    "creditValue" to e.creditIssuedValue,
    "prevColl" to e.recoveredCredit,
    "fieldExpenses" to e.fieldExpenses,
    "fieldExpenseItems" to JsonCodecs.decodeLineItems(e.expenseItemsJson).map { mapOf("category" to it.category, "amount" to it.amount) },
    "commissionPaidItems" to JsonCodecs.decodeLineItems(e.commissionItemsJson).map { mapOf("category" to it.category, "amount" to it.amount) },
    "totalExpected" to e.expectedCash,
    "received" to e.receivedCash,
    "commissionPerUnit" to e.commissionPerUnit,
    "commissionPaid" to e.commissionPaid,
    "grossCommission" to e.grossCommission,
    "commissionPayable" to e.commissionPayable,
    "statusText" to e.status,
    "statusClass" to e.statusClass,
    "linkedSalesIds" to JsonCodecs.decodeStrings(e.linkedSalesIdsJson),
    "linkedRepSalesIds" to JsonCodecs.decodeStrings(e.linkedRepSalesIdsJson),
    "linkedRepReturnIds" to JsonCodecs.decodeStrings(e.linkedRepReturnIdsJson),
    "editHistory" to editHistoryToMaps(e.editHistoryJson),
    "createdAt" to e.createdAt,
    "updatedAt" to e.updatedAt,
    "timestamp" to e.dateEpoch,
    "syncedAt" to isoUtc(e.updatedAt),
  )

  // ---------- SINGLETON DOCS: STORES, FORMULAS, DAILY_LEDGER ----------

  fun storesListDocPayload(stores: List<StoreEntity>, ts: Long): Map<String, Any?> = linkedMapOf(
    "stores" to stores.filter { !it.isDeleted }.map { s ->
      linkedMapOf(
        "key" to s.storeKey,
        "name" to s.name,
        "formulaId" to s.formulaId,
        "salePrice" to s.salePricePerKg,
      )
    },
    "stores_timestamp" to ts,
    "synced" to isoUtc(ts),
  )

  fun formulasConfigDocPayload(
    formulas: List<FormulaEntity>,
    savedCalcs: List<SavedMixCalcEntity>,
    materials: Map<String, RawMaterialEntity>,
    ts: Long,
  ): Map<String, Any?> {
    val calcsByFormula = savedCalcs.filter { !it.isDeleted }.groupBy { it.formulaId }
    val formulasList = formulas.filter { !it.isDeleted }.map { f ->
      val ings = JsonCodecs.decodeIngredients(f.ingredientsJson).map { ing ->
        linkedMapOf<String, Any?>(
          "id" to ing.refId,
          "name" to ing.refName,
          "quantity" to ing.qtyKg,
          "cost" to (materials[ing.refId]?.avgCostPerKg ?: 0.0),
          "formulaId" to if (ing.refType == "FORMULA") ing.refId else null,
        )
      }
      val calcs = calcsByFormula[f.id].orEmpty().map { c ->
        linkedMapOf(
          "id" to c.id,
          "kind" to if (c.kind == "CORRECTION") "fix" else "batch",
          "name" to c.name,
          "unit" to c.unit,
          "totalWeight" to c.inputValue,
          "summaryText" to c.summaryText,
          "createdAt" to c.createdAt,
          "updatedAt" to c.updatedAt,
        )
      }
      linkedMapOf(
        "id" to f.id,
        "name" to f.name,
        "additionalCost" to f.additionalCostPerUnit,
        "factor" to f.costAdjustmentFactor,
        "ingredients" to ings,
        "savedCalcs" to calcs,
      )
    }
    val defaults = linkedMapOf<String, Any?>()
    val costs = linkedMapOf<String, Double>()
    val adjustment = linkedMapOf<String, Double>()
    val tracking = linkedMapOf<String, Map<String, Double>>()
    formulas.filter { !it.isDeleted }.forEach { f ->
      defaults[f.id] = JsonCodecs.decodeIngredients(f.ingredientsJson).map { ing ->
        mapOf("id" to ing.refId, "name" to ing.refName, "quantity" to ing.qtyKg)
      }
      costs[f.id] = f.additionalCostPerUnit
      adjustment[f.id] = f.costAdjustmentFactor
      tracking[f.id] = mapOf("available" to f.unitsAvailable, "used" to f.unitsUsed)
    }
    return linkedMapOf(
      "formulas" to formulasList,
      "defaults" to defaults,
      "costs" to costs,
      "adjustment" to adjustment,
      "tracking" to tracking,
      "formulas_timestamp" to ts,
      "defaults_timestamp" to ts,
      "costs_timestamp" to ts,
      "adjustment_timestamp" to ts,
      "tracking_timestamp" to ts,
      "synced" to isoUtc(ts),
    )
  }

  fun dailyLedgerEntryToMap(l: DailyLedgerEntity): Map<String, Any?> = linkedMapOf(
    "id" to l.id,
    "type" to l.type,
    "amount" to l.amount,
    "category" to l.category,
    "scope" to l.scope,
    "mode" to l.mode,
    "toMode" to l.toMode,
    "party" to l.party,
    "note" to l.note,
    "ref" to l.refNo,
    "date" to l.dateStr.ifBlank { SarimFormat.dateInput(l.dateEpoch) },
    "sourceType" to l.sourceType,
    "sourceId" to l.sourceId,
    "link" to JsonCodecs.decodeLedgerLink(l.linkJson),
    "createdAt" to l.createdAt,
    "timestamp" to l.dateEpoch,
    "updatedAt" to l.updatedAt,
  )

  fun dailyLedgerDocPayload(rows: List<DailyLedgerEntity>, ts: Long): Map<String, Any?> = linkedMapOf(
    "daily_ledger" to rows.filter { !it.isDeleted }.map { dailyLedgerEntryToMap(it) },
    "daily_ledger_timestamp" to ts,
    "synced" to isoUtc(ts),
  )

  fun mapIngredientsFromWeb(raw: Any?): List<FormulaIngredient> {
    val arr = raw as? List<*> ?: return emptyList()
    return arr.mapNotNull { item ->
      val obj = item as? Map<*, *> ?: return@mapNotNull null
      val subFormulaId = obj["formulaId"]?.toString()?.takeIf { it.isNotBlank() }
      FormulaIngredient(
        refType = if (subFormulaId != null) "FORMULA" else "MATERIAL",
        refId = subFormulaId ?: str(obj["id"]),
        refName = str(obj["name"]),
        qtyKg = num(obj["quantity"]),
      )
    }
  }

  fun paymentTransferToOutInMaps(e: PaymentTransferEntity): Pair<Pair<String, Map<String, Any?>>, Pair<String, Map<String, Any?>>> {
    val date = e.dateStr.ifBlank { SarimFormat.dateInput(e.dateEpoch) }
    val outMap = linkedMapOf<String, Any?>(
      "id" to e.outDocId,
      "entityId" to e.fromEntityId,
      "entityName" to e.fromEntityName,
      "type" to "OUT",
      "amount" to e.amount,
      "date" to date,
      "description" to e.note.ifBlank { "Transfer to ${e.toEntityName}" },
      "isTransfer" to true,
      "transferPairId" to e.id,
      "transferPeerEntityId" to e.toEntityId,
      "transferPeerEntityName" to e.toEntityName,
      "createdAt" to e.createdAt,
      "updatedAt" to e.updatedAt,
      "timestamp" to e.dateEpoch,
      "syncedAt" to isoUtc(e.updatedAt),
    )
    val inMap = linkedMapOf<String, Any?>(
      "id" to e.inDocId,
      "entityId" to e.toEntityId,
      "entityName" to e.toEntityName,
      "type" to "IN",
      "amount" to e.amount,
      "date" to date,
      "description" to e.note.ifBlank { "Transfer from ${e.fromEntityName}" },
      "isTransfer" to true,
      "transferPairId" to e.id,
      "transferPeerEntityId" to e.fromEntityId,
      "transferPeerEntityName" to e.fromEntityName,
      "createdAt" to e.createdAt,
      "updatedAt" to e.updatedAt,
      "timestamp" to e.dateEpoch,
      "syncedAt" to isoUtc(e.updatedAt),
    )
    return (e.outDocId to outMap) to (e.inDocId to inMap)
  }

  fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
    val out = linkedMapOf<String, Any?>()
    val keys = obj.keys()
    while (keys.hasNext()) {
      val k = keys.next()
      out[k] = when (val v = obj.opt(k)) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(v)
        is JSONArray -> jsonArrayToList(v)
        else -> v
      }
    }
    return out
  }

  private fun jsonArrayToList(arr: JSONArray): List<Any?> {
    return (0 until arr.length()).map { idx ->
      when (val v = arr.opt(idx)) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(v)
        is JSONArray -> jsonArrayToList(v)
        else -> v
      }
    }
  }
}
