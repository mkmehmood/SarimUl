package com.example.data.remote

import com.example.data.repository.SarimSavedCalcs
import com.example.data.local.*
import com.example.data.model.LineItemBreakdown
import com.example.data.remote.SarimWebSchema.bool
import com.example.data.remote.SarimWebSchema.num
import com.example.data.remote.SarimWebSchema.parseEpoch
import com.example.data.remote.SarimWebSchema.str
import com.example.util.SarimFormat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reverse mappers converting Firestore/web-repo JSON payloads back into Room entities.
 * Mirrors the exact keys documented in `modules/sync.js`, `modules/business.js`,
 * `modules/ledger-sync.js`, and feature tabs of the cloned Sarim web repo.
 */
object SarimWebPullMappers {

  fun mapToStore(raw: Map<*, *>): StoreEntity? {
    val key = str(raw["key"], str(raw["storeKey"])).trim()
    val name = str(raw["name"]).trim()
    if (key.isBlank() || name.isBlank()) return null
    return StoreEntity(
      storeKey = key,
      name = name.uppercase(),
      formulaId = str(raw["formulaId"]),
      salePricePerKg = num(raw["salePrice"], num(raw["salePricePerKg"])),
      isDeleted = bool(raw["isDeleted"]),
      deletedAt = (raw["deletedAt"] as? Number)?.toLong(),
      syncPending = false,
      updatedAt = parseEpoch(raw["updatedAt"]),
    )
  }

  fun mapToMaterial(id: String, m: Map<String, Any?>): RawMaterialEntity? {
    if (bool(m["_placeholder"]) || bool(m["isPurchaseLot"])) return null
    val name = str(m["name"]).trim()
    if (name.isBlank()) return null
    return RawMaterialEntity(
      id = id,
      name = name,
      unitName = str(m["purchaseUnitName"], str(m["unitName"], "bag")),
      conversionFactorKg = num(m["conversionFactor"], num(m["conversionFactorKg"], 1.0)),
      stockKg = num(m["quantity"], num(m["stockKg"])),
      collectedKg = num(m["collectedKg"]),
      avgCostPerKg = num(m["cost"], num(m["avgCostPerKg"])),
      pendingSupplierAmount = num(m["totalPayable"], num(m["pendingSupplierAmount"])),
      unit = str(m["unit"], "kg"),
      paymentStatus = str(m["paymentStatus"], "paid"),
      supplierId = m["supplierId"]?.toString(),
      supplierName = m["supplierName"]?.toString(),
      purchasesTracked = if (m.containsKey("purchasesTracked")) bool(m["purchasesTracked"], true) else true,
      isDeleted = bool(m["isDeleted"]),
      syncPending = false,
      createdAt = parseEpoch(m["createdAt"]),
      updatedAt = parseEpoch(m["updatedAt"] ?: m["createdAt"]),
    )
  }

  fun mapToPurchaseLot(id: String, m: Map<String, Any?>): RawMaterialPurchaseEntity? {
    if (bool(m["_placeholder"]) || !bool(m["isPurchaseLot"])) return null
    val matId = str(m["materialRefId"], str(m["materialId"]))
    if (matId.isBlank()) return null
    val pDate = str(m["purchaseDate"], SarimFormat.dateInput(System.currentTimeMillis()))
    val epoch = parseEpoch(m["timestamp"] ?: m["createdAt"], pDate)
    return RawMaterialPurchaseEntity(
      id = id,
      materialId = matId,
      materialName = str(m["materialName"]),
      dateEpoch = epoch,
      unitName = str(m["purchaseUnitName"], "bag"),
      unitsBought = num(m["purchaseQuantity"]),
      weightPerUnitKg = num(m["conversionFactor"], 1.0),
      costPerUnit = num(m["purchaseCost"]),
      totalKg = num(m["purchaseKg"]),
      totalAmount = num(m["totalValue"]),
      supplierMode = if (!m["supplierName"]?.toString().isNullOrBlank()) "EXISTING" else "NONE",
      supplierId = m["supplierId"]?.toString(),
      supplierName = m["supplierName"]?.toString(),
      paymentStatus = str(m["paymentStatus"], "pending"),
      totalPayable = num(m["totalPayable"]),
      purchaseDate = pDate,
      isDeleted = bool(m["isDeleted"]),
      syncPending = false,
      createdAt = parseEpoch(m["createdAt"] ?: epoch),
      updatedAt = parseEpoch(m["updatedAt"] ?: epoch),
    )
  }

  fun mapToFactoryBatch(id: String, m: Map<String, Any?>): FactoryBatchEntity? {
    if (bool(m["_placeholder"])) return null
    val dateStr = str(m["date"], SarimFormat.dateInput(System.currentTimeMillis()))
    val epoch = parseEpoch(m["timestamp"] ?: m["createdAt"], dateStr)
    val units = num(m["units"], num(m["unitsProduced"]))
    val totalCost = num(m["totalCost"])
    val materialsCost = num(m["materialsCost"])
    val additionalCost = num(m["additionalCost"])
    val rawUsed = num(m["rawMaterialsUsed"], num(m["rawUsedKg"]))
    val matsUsed = (m["materialsUsed"] as? List<*>)?.mapNotNull { item ->
      val obj = item as? Map<*, *> ?: return@mapNotNull null
      JsonCodecs.FormulaMaterialUsed(
        id = str(obj["id"]),
        name = str(obj["name"]),
        quantity = num(obj["quantity"]),
        cost = num(obj["cost"]),
      )
    }.orEmpty()
    return FactoryBatchEntity(
      id = id,
      dateEpoch = epoch,
      formulaId = str(m["formulaId"]),
      formulaName = str(m["formulaName"], "Formula"),
      unitsProduced = units,
      rawUsedKg = rawUsed,
      outputKg = num(m["outputKg"], rawUsed),
      materialsCost = materialsCost,
      additionalCost = additionalCost,
      totalCost = totalCost,
      costPerUnit = if (units > 0.0001) totalCost / units else 0.0,
      store = str(m["store"]),
      dateStr = dateStr,
      timeStr = str(m["time"]),
      materialsUsedJson = JsonCodecs.encodeFormulaMaterialsUsed(matsUsed),
      isDeleted = bool(m["isDeleted"]),
      syncPending = false,
      createdAt = parseEpoch(m["createdAt"] ?: epoch),
      updatedAt = parseEpoch(m["updatedAt"] ?: epoch),
    )
  }

  fun mapToReturnLog(id: String, m: Map<String, Any?>): ReturnLogEntity? {
    if (bool(m["_placeholder"])) return null
    val dateStr = str(m["date"], SarimFormat.dateInput(System.currentTimeMillis()))
    val epoch = parseEpoch(m["timestamp"] ?: m["createdAt"], dateStr)
    return ReturnLogEntity(
      id = id,
      date = dateStr,
      time = str(m["time"]),
      store = str(m["store"]),
      quantity = num(m["quantity"]),
      seller = str(m["seller"]),
      dateEpoch = epoch,
      isDeleted = bool(m["isDeleted"]),
      syncPending = false,
      createdAt = parseEpoch(m["createdAt"] ?: epoch),
      updatedAt = parseEpoch(m["updatedAt"] ?: epoch),
    )
  }

  fun mapToDailyLedger(raw: Map<*, *>): DailyLedgerEntity? {
    val id = str(raw["id"]).trim()
    if (id.isBlank()) return null
    val dateStr = str(raw["date"], SarimFormat.dateInput(System.currentTimeMillis()))
    val epoch = parseEpoch(raw["timestamp"] ?: raw["dateEpoch"], dateStr)
    @Suppress("UNCHECKED_CAST")
    val linkMap = raw["link"] as? Map<String, Any?>
    return DailyLedgerEntity(
      id = id,
      dateEpoch = epoch,
      type = str(raw["type"], "IN"),
      amount = num(raw["amount"]),
      mode = str(raw["mode"], "Cash"),
      toMode = str(raw["toMode"]),
      party = str(raw["party"]),
      refNo = str(raw["ref"], str(raw["refNo"])),
      note = str(raw["note"]),
      category = str(raw["category"], "General"),
      sourceType = str(raw["sourceType"], "MANUAL"),
      sourceId = str(raw["sourceId"]),
      scope = str(raw["scope"]),
      dateStr = dateStr,
      linkJson = JsonCodecs.encodeLedgerLink(linkMap),
      isDeleted = bool(raw["isDeleted"]),
      syncPending = false,
      createdAt = parseEpoch(raw["createdAt"] ?: epoch),
      updatedAt = parseEpoch(raw["updatedAt"] ?: epoch),
    )
  }

  fun mapToSettlement(id: String, m: Map<String, Any?>): CalculatorSettlementEntity? {
    if (bool(m["_placeholder"])) return null
    val dateStr = str(m["date"], SarimFormat.dateInput(System.currentTimeMillis()))
    val epoch = parseEpoch(m["timestamp"] ?: m["createdAt"], dateStr)
    val totalSold = num(m["totalSold"])
    val returned = num(m["returned"])
    val expired = num(m["expired"])
    val shared = num(m["shared"])
    val netSold = (totalSold - returned - expired - shared).coerceAtLeast(0.0)
    val expected = num(m["totalExpected"])
    val received = num(m["received"])
    val expItems = decodeRemoteLineItems(m["fieldExpenseItems"])
    val commItems = decodeRemoteLineItems(m["commissionPaidItems"])
    val linkedSales = (m["linkedSalesIds"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
    val linkedRepSales = (m["linkedRepSalesIds"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
    val linkedRepReturns = (m["linkedRepReturnIds"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
    val statusText = str(m["statusText"], if (received >= expected) "OK" else "SHORT")
    val defaultStatusClass = when {
      statusText.contains("SHORT", true) -> "status-short"
      statusText.contains("OVER", true) -> "status-over"
      else -> "status-ok"
    }
    return CalculatorSettlementEntity(
      id = id,
      repName = str(m["seller"], "Direct"),
      fromDateEpoch = parseEpoch(m["fromDateEpoch"] ?: epoch),
      toDateEpoch = parseEpoch(m["toDateEpoch"] ?: epoch),
      dateEpoch = epoch,
      totalSoldKg = totalSold,
      returnedKg = returned,
      expiredKg = expired,
      sharedDeductionKg = shared,
      netSoldKg = netSold,
      returnStoreKey = str(m["returnStore"]),
      creditSalesQty = num(m["creditQty"]),
      commissionPerUnit = num(m["commissionPerUnit"]),
      commissionPaid = num(m["commissionPaid"]),
      commissionItemsJson = JsonCodecs.encodeLineItems(commItems),
      recoveredCredit = num(m["prevColl"]),
      fieldExpenses = num(m["fieldExpenses"]),
      expenseItemsJson = JsonCodecs.encodeLineItems(expItems),
      receivedCash = received,
      expectedCash = expected,
      status = statusText,
      statusDiff = received - expected,
      grossCommission = num(m["grossCommission"]),
      commissionPayable = num(m["commissionPayable"]),
      totalCost = num(m["totalCost"]),
      grossRevenue = num(m["revenue"]),
      netProfit = num(m["profit"]),
      creditIssuedValue = num(m["creditValue"]),
      dateStr = dateStr,
      returnEntryId = m["returnEntryId"]?.toString(),
      returnLogId = m["returnLogId"]?.toString(),
      statusClass = str(m["statusClass"], defaultStatusClass),
      returnRep = m["returnRep"]?.toString(),
      returnType = m["returnType"]?.toString(),
      transferSaleId = m["transferSaleId"]?.toString(),
      expiredApplied = if (m.containsKey("expiredApplied")) bool(m["expiredApplied"], true) else true,
      linkedSalesIdsJson = JsonCodecs.encodeStrings(linkedSales),
      linkedRepSalesIdsJson = JsonCodecs.encodeStrings(linkedRepSales),
      linkedRepReturnIdsJson = JsonCodecs.encodeStrings(linkedRepReturns),
      editHistoryJson = SarimWebSchema.mapEditHistoryFromWeb(m["editHistory"]),
      isDeleted = bool(m["isDeleted"]),
      syncPending = false,
      createdAt = parseEpoch(m["createdAt"] ?: epoch),
      updatedAt = parseEpoch(m["updatedAt"] ?: epoch),
    )
  }

  private fun decodeRemoteLineItems(raw: Any?): List<LineItemBreakdown> {
    val list = raw as? List<*> ?: return emptyList()
    return list.mapNotNull { entry ->
      val map = entry as? Map<*, *> ?: return@mapNotNull null
      val cat = str(map["category"], str(map["name"], "Other"))
      val amt = num(map["amount"])
      if (amt == 0.0 && cat.isBlank()) null else LineItemBreakdown(category = cat, amount = amt)
    }
  }

  fun mapToApproval(id: String, m: Map<String, Any?>): ApprovalRequestEntity? {
    if (bool(m["_placeholder"])) return null
    val statusRaw = str(m["status"], "pending").uppercase()
    val status = when {
      statusRaw.contains("APPROV") -> "APPROVED"
      statusRaw.contains("DISCARD") || statusRaw.contains("REJECT") -> "DISCARDED"
      else -> "PENDING"
    }
    val subRaw = m["submittedBy"]
    val subMap = subRaw as? Map<*, *>
    val submittedByUid = subMap?.get("uid")?.toString()?.trim().orEmpty()
    val submittedByEmail = subMap?.get("email")?.toString()?.trim().orEmpty()
    val submittedByName = subMap?.let {
      str(it["name"]).ifBlank { str(it["email"]) }
    }?.ifBlank { "Team Member" }
      ?: str(subRaw).takeIf { it.isNotBlank() && !it.startsWith("{") }
      ?: "Team Member"

    val col = str(m["collection"])
    val docId = str(m["docId"])
    val action = str(m["action"], "set")
    val isEdit = bool(m["isEdit"])

    @Suppress("UNCHECKED_CAST")
    val rawDataMap = m["data"] as? Map<String, Any?>
    val dataObj = rawDataMap?.let { encodeJsonMap(it) } ?: str(m["data"], "{}").ifBlank { "{}" }

    val summaryText = when (val s = m["summary"]) {
      is Map<*, *> -> {
        val t = str(s["title"])
        val b = str(s["body"])
        listOf(t, b).filter { it.isNotBlank() }.joinToString(" — ").ifBlank { id }
      }
      is String -> s.ifBlank {
        val computed = SarimWebSchema.summarizePending(col, docId, action, isEdit, rawDataMap)
        listOf(computed["title"].orEmpty(), computed["body"].orEmpty()).filter { it.isNotBlank() }.joinToString(" — ")
      }
      else -> {
        val computed = SarimWebSchema.summarizePending(col, docId, action, isEdit, rawDataMap)
        listOf(computed["title"].orEmpty(), computed["body"].orEmpty()).filter { it.isNotBlank() }.joinToString(" — ")
      }
    }

    val amt = rawDataMap?.let { d ->
      val tv = d["totalValue"] ?: d["amount"] ?: d["totalSale"]
      num(tv, 0.0)
    } ?: 0.0

    val category = if (col.isNotBlank()) SarimWebSchema.categoryForPending(col) else "Other"
    val created = parseEpoch(m["submittedAt"] ?: m["createdAt"])
    return ApprovalRequestEntity(
      id = id,
      submittedBy = submittedByName,
      submittedRole = str(m["submittedRole"], if (submittedByEmail.isNotBlank()) submittedByEmail else "Member"),
      category = category,
      actionKind = if (action == "delete") "Delete" else if (isEdit) "Change" else "New",
      targetSummary = summaryText,
      amount = amt,
      payloadJson = dataObj,
      status = status,
      targetCollection = col,
      docId = docId,
      action = action,
      isEdit = isEdit,
      syncPending = false,
      createdAt = created,
      submittedByUid = submittedByUid,
      submittedByEmail = submittedByEmail,
      updatedAt = parseEpoch(m["decidedAt"] ?: m["updatedAt"] ?: created),
    )
  }

  fun mapToAppAccount(uid: String, m: Map<String, Any?>): AppAccountEntity {
    val roles = com.example.data.model.RolesHelper.normalizeRoles(m)
    val email = str(m["email"]).trim().lowercase()
    val name = str(m["displayName"]).ifBlank { str(m["name"]) }.ifBlank { email.substringBefore("@") }
    return AppAccountEntity(
      id = uid,
      uid = uid,
      email = email,
      name = name,
      rolesCsv = roles.joinToString(",") { it.uppercase() },
      syncPending = false,
      createdAt = parseEpoch(m["createdAt"], null),
      approved = m["approved"] != false,
      ownerUid = str(m["ownerUid"]).trim(),
      createdBy = str(m["createdBy"]).trim(),
    )
  }

  fun mapFormulasConfigDoc(
    doc: Map<String, Any>,
    existingFormulas: Map<String, FormulaEntity>,
  ): Pair<List<FormulaEntity>, List<SavedMixCalcEntity>> {
    @Suppress("UNCHECKED_CAST")
    val trackingMap = doc["tracking"] as? Map<String, Any?> ?: emptyMap()
    @Suppress("UNCHECKED_CAST")
    val costsMap = doc["costs"] as? Map<String, Any?> ?: emptyMap()
    @Suppress("UNCHECKED_CAST")
    val adjMap = doc["adjustment"] as? Map<String, Any?> ?: emptyMap()
    val rawFormulas = doc["formulas"] as? List<*> ?: emptyList<Any>()
    val ts = parseEpoch(doc["formulas_timestamp"] ?: doc["tracking_timestamp"])

    val formulasOut = mutableListOf<FormulaEntity>()
    val calcsOut = mutableListOf<SavedMixCalcEntity>()

    rawFormulas.forEach { item ->
      val m = item as? Map<*, *> ?: return@forEach
      val id = str(m["id"]).trim()
      val name = str(m["name"]).trim()
      if (id.isBlank() || name.isBlank()) return@forEach
      val ings = SarimWebSchema.mapIngredientsFromWeb(m["ingredients"])
      @Suppress("UNCHECKED_CAST")
      val tr = trackingMap[id] as? Map<String, Any?>
      val existing = existingFormulas[id]
      formulasOut += FormulaEntity(
        id = id,
        name = name,
        additionalCostPerUnit = num(m["additionalCost"], num(costsMap[id], existing?.additionalCostPerUnit ?: 0.0)),
        costAdjustmentFactor = num(m["costAdjustmentFactor"], num(m["factor"], num(adjMap[id], existing?.costAdjustmentFactor ?: 1.0))),
        ingredientsJson = if (ings.isNotEmpty()) JsonCodecs.encodeIngredients(ings) else (existing?.ingredientsJson ?: "[]"),
        unitsAvailable = num(tr?.get("available"), existing?.unitsAvailable ?: 0.0),
        unitsUsed = num(tr?.get("used"), existing?.unitsUsed ?: 0.0),
        syncPending = false,
        createdAt = existing?.createdAt ?: ts,
        updatedAt = ts,
      )
      val savedCalcsRaw = m["savedCalcs"] as? List<*> ?: emptyList<Any>()
      savedCalcsRaw.forEach { scRaw ->
        val sc = scRaw as? Map<*, *> ?: return@forEach
        SarimSavedCalcs.fromWebRecord(
          formulaId = id,
          formulaName = name,
          sc = sc,
          fallbackTs = ts,
          createdAt = parseEpoch(sc["createdAt"] ?: ts),
          updatedAt = parseEpoch(sc["updatedAt"] ?: ts),
        )?.let { calcsOut += it }
      }
    }
    return formulasOut to calcsOut
  }

  fun reconstructStockTransfers(docs: List<Pair<String, Map<String, Any>>>): List<StockTransferEntity> {
    val grouped = docs.filter { bool(it.second["isTransfer"]) && !bool(it.second["_placeholder"]) }
      .groupBy { (_, m) -> str(m["transferPairId"]) }
    return grouped.mapNotNull { (pairId, items) ->
      if (pairId.isBlank()) return@mapNotNull null
      val outEntry = items.firstOrNull { str(it.second["transferDirection"]) == "out" } ?: return@mapNotNull null
      val inEntry = items.firstOrNull { str(it.second["transferDirection"]) == "in" }
      val mOut = outEntry.second
      val dateStr = str(mOut["date"], SarimFormat.dateInput(System.currentTimeMillis()))
      val epoch = parseEpoch(mOut["timestamp"] ?: mOut["createdAt"], dateStr)
      val fromKey = str(mOut["store"])
      val toKey = str(mOut["transferPeerStore"], inEntry?.second?.get("store")?.toString() ?: "")
      StockTransferEntity(
        id = pairId,
        dateEpoch = epoch,
        fromStoreKey = fromKey,
        fromStoreName = fromKey.uppercase(),
        toStoreKey = toKey,
        toStoreName = toKey.uppercase(),
        quantityKg = num(mOut["net"]),
        note = str(mOut["transferNote"]),
        dateStr = dateStr,
        outDocId = outEntry.first,
        inDocId = inEntry?.first ?: com.example.data.model.SarimUuid.generateUUID(com.example.data.model.SarimUuid.PREFIX_TRF),
        syncPending = false,
        createdAt = parseEpoch(mOut["createdAt"] ?: epoch),
        updatedAt = parseEpoch(mOut["updatedAt"] ?: epoch),
      )
    }
  }

  fun reconstructPaymentTransfers(docs: List<Pair<String, Map<String, Any>>>): List<PaymentTransferEntity> {
    val grouped = docs.filter { bool(it.second["isTransfer"]) && !bool(it.second["_placeholder"]) }
      .groupBy { (_, m) -> str(m["transferPairId"]) }
    return grouped.mapNotNull { (pairId, items) ->
      if (pairId.isBlank()) return@mapNotNull null
      val outEntry = items.firstOrNull { str(it.second["type"]).equals("OUT", true) } ?: return@mapNotNull null
      val inEntry = items.firstOrNull { str(it.second["type"]).equals("IN", true) }
      val mOut = outEntry.second
      val dateStr = str(mOut["date"], SarimFormat.dateInput(System.currentTimeMillis()))
      val epoch = parseEpoch(mOut["timestamp"] ?: mOut["createdAt"], dateStr)
      PaymentTransferEntity(
        id = pairId,
        dateEpoch = epoch,
        fromEntityId = str(mOut["entityId"]),
        fromEntityName = str(mOut["entityName"]),
        toEntityId = str(mOut["transferPeerEntityId"], inEntry?.second?.get("entityId")?.toString() ?: ""),
        toEntityName = str(mOut["transferPeerEntityName"], inEntry?.second?.get("entityName")?.toString() ?: ""),
        amount = num(mOut["amount"]),
        note = str(mOut["description"]),
        dateStr = dateStr,
        outDocId = outEntry.first,
        inDocId = inEntry?.first ?: com.example.data.model.SarimUuid.generateUUID(com.example.data.model.SarimUuid.PREFIX_PAY),
        syncPending = false,
        createdAt = parseEpoch(mOut["createdAt"] ?: epoch),
        updatedAt = parseEpoch(mOut["updatedAt"] ?: epoch),
      )
    }
  }

  fun mapToDeletionTombstone(id: String, m: Map<String, Any?>): DeletionTombstoneEntity? {
    if (bool(m["_placeholder"])) return null
    val recordId = str(m["recordId"], id).trim()
    if (recordId.isBlank()) return null
    val col = str(m["collection"], "unknown")
    val delAt = parseEpoch(m["deletedAt"])
    @Suppress("UNCHECKED_CAST")
    val snapMap = m["snapshot"] as? Map<String, Any?>
    val snapJson = snapMap?.let { encodeJsonMap(it) }
    return DeletionTombstoneEntity(
      id = id,
      recordId = recordId,
      collection = col,
      recordType = str(m["recordType"], col),
      deletedAt = delAt,
      expiresAt = parseEpoch(m["expiresAt"], null).let { if (it > delAt) it else delAt + SarimWebSchema.TOMBSTONE_EXPIRY_MS },
      syncedToCloud = true,
      displayName = m["displayName"]?.toString(),
      displayDetail = m["displayDetail"]?.toString(),
      displayAmount = m["displayAmount"]?.toString(),
      snapshotJson = snapJson,
      deletedBy = str(m["deleted_by"], "Admin"),
      deletionVersion = str(m["deletion_version"], "2.0"),
      groupId = m["_groupId"]?.toString(),
    )
  }

  fun jsonObjectToMapList(arr: JSONArray?): List<Map<String, Any?>> {
    if (arr == null) return emptyList()
    return (0 until arr.length()).mapNotNull { idx ->
      arr.optJSONObject(idx)?.let { SarimWebSchema.jsonObjectToMap(it) }
    }
  }

  private fun encodeJsonMap(map: Map<*, *>): String = buildString {
    append('{')
    var first = true
    for ((k, v) in map) {
      if (k == null) continue
      if (!first) append(',')
      first = false
      append('"').append(escapeJsonString(k.toString())).append("\":")
      append(encodeJsonValue(v))
    }
    append('}')
  }

  private fun encodeJsonValue(v: Any?): String = when (v) {
    null -> "null"
    is Boolean -> v.toString()
    is Number -> v.toString()
    is Map<*, *> -> encodeJsonMap(v)
    is Iterable<*> -> v.joinToString(",", prefix = "[", postfix = "]") { encodeJsonValue(it) }
    is Array<*> -> v.joinToString(",", prefix = "[", postfix = "]") { encodeJsonValue(it) }
    else -> "\"${escapeJsonString(v.toString())}\""
  }

  private fun escapeJsonString(value: String): String = buildString(value.length + 4) {
    for (c in value) {
      when (c) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        '\b' -> append("\\b")
        '\u000C' -> append("\\f")
        else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
      }
    }
  }
}
