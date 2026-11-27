package com.example.data.repository

import com.example.data.local.*
import com.example.data.model.SarimUuid
import com.example.data.remote.SarimWebSchema
import com.example.data.remote.UuidSyncRegistry
import com.example.util.SarimFormat
import org.json.JSONArray
import org.json.JSONObject

class SarimRecycleRestoreEngine(
  private val dao: SarimDao,
  private val uuidRegistry: UuidSyncRegistry,
) {

  private data class RestoreState(
    var touchedLedger: Boolean = false,
    var touchedFormulas: Boolean = false,
    val repricingDays: LinkedHashSet<Pair<String, String>> = linkedSetOf(),
  )

  suspend fun removeTombstoneAndEnqueueCloudDelete(docId: String, now: Long) {
    dao.deleteDeletion(docId)
    dao.removeOutboxForDoc(SarimWebSchema.COL_DELETIONS, docId)
    dao.insertOutbox(
      NdappOutboxEntity(
        id = SarimUuid.generateUUID(SarimUuid.PREFIX_OB, tsMs = now),
        action = "delete",
        collection = SarimWebSchema.COL_DELETIONS,
        docId = docId,
        payload = null,
        createdAt = now,
      )
    )
  }

  private fun mapValToJson(v: Any?): Any = when (v) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().apply {
      v.forEach { (k, inner) -> if (k != null && inner != null) put(k.toString(), mapValToJson(inner)) }
    }
    is Iterable<*> -> JSONArray().apply { v.forEach { put(mapValToJson(it)) } }
    else -> v
  }

  private fun mapToJson(map: Map<String, Any?>): String {
    val obj = JSONObject()
    map.forEach { (k, v) -> if (v != null) obj.put(k, mapValToJson(v)) }
    return obj.toString()
  }

  private suspend fun enqueueOutboxSet(collection: String, docId: String, payloadMap: Map<String, Any?>, now: Long) {
    dao.removeOutboxForDoc(collection, docId)
    dao.insertOutbox(
      NdappOutboxEntity(
        id = SarimUuid.generateUUID(SarimUuid.PREFIX_OB, tsMs = now),
        action = "set",
        collection = collection,
        docId = docId,
        payload = mapToJson(payloadMap),
        createdAt = now,
      )
    )
    uuidRegistry.markLocalModified(collection, docId, now)
  }

  private suspend fun touchTimestampKey(userKey: String, collection: String, now: Long) {
    dao.upsertKv(
      KvStoreEntity(
        fullKey = userKey,
        userKey = userKey,
        collection = collection,
        rowType = "meta",
        value = now.toString(),
        ts = now,
        createdAt = now,
      )
    )
    uuidRegistry.markLocalModified(collection, null, now)
  }

  private fun entityTypeForTombstone(t: DeletionTombstoneEntity): String? {
    val rt = t.recordType.trim().lowercase()
    return when {
      rt == "purchase_lot" -> "PURCHASE_LOT"
      rt == "stock_transfer" -> "STOCK_TRANSFER"
      rt == "payment_transfer" -> "PAYMENT_TRANSFER"
      t.collection == SarimWebSchema.COL_SALES || t.collection == SarimWebSchema.COL_REP -> "SALE"
      t.collection == SarimWebSchema.COL_PRODUCTION -> "PRODUCTION"
      t.collection == SarimWebSchema.COL_CALCULATOR -> "SETTLEMENT"
      t.collection == SarimWebSchema.COL_FACTORY -> "BATCH"
      t.collection == SarimWebSchema.COL_TRANSACTIONS -> "PAYMENT"
      t.collection == SarimWebSchema.COL_EXPENSES -> "EXPENSE"
      t.collection == SarimWebSchema.COL_RETURNS -> "RETURN"
      t.collection == SarimWebSchema.COL_DAILY_LEDGER -> "LEDGER"
      t.collection == SarimWebSchema.COL_FORMULAS -> "FORMULA"
      t.collection == SarimWebSchema.COL_INVENTORY -> "MATERIAL"
      t.collection == SarimWebSchema.COL_CUSTOMERS || t.collection == SarimWebSchema.COL_CLIENTS -> "CUSTOMER"
      t.collection == SarimWebSchema.COL_ENTITIES -> "ENTITY"
      t.collection == SarimWebSchema.COL_STORES -> "STORE"
      else -> null
    }
  }

  suspend fun restoreRecycleItem(item: RecycleBinRecord) {
    val now = System.currentTimeMillis()
    val targets = collectRestoreTargets(item)
    val targetIdsSet = targets.keys.map { it.second }.toSet()
    val ordered = targets.entries.sortedWith(
      compareBy<Map.Entry<Pair<String, String>, Boolean>> { SarimLinkEngine.restoreSortOrder(it.key.first, it.value) }
        .thenBy { it.key.second }
    ).map { it.key }

    preValidateRestoreTargets(ordered, targets.keys)

    val state = RestoreState()
    for ((type, id) in ordered) {
      restoreSingleRecycleTarget(type, id, targetIdsSet, now, state)
      removeTombstoneAndEnqueueCloudDelete(id, now)
    }

    if (state.touchedFormulas) {
      touchTimestampKey("formulas_timestamp", SarimWebSchema.COL_FORMULAS, now)
    }
    if (state.touchedLedger) {
      touchTimestampKey("daily_ledger_timestamp", SarimWebSchema.COL_DAILY_LEDGER, now)
    }
    if (state.repricingDays.isNotEmpty()) {
      repriceRestoredProductionDays(state.repricingDays, now)
    }
  }

  private suspend fun collectRestoreTargets(item: RecycleBinRecord): LinkedHashMap<Pair<String, String>, Boolean> {
    val allDeletions = dao.getAllDeletions()
    val primaryTombstone = allDeletions.firstOrNull { it.id == item.id || it.recordId == item.id }
    val groupId = primaryTombstone?.groupId?.takeIf { it.isNotBlank() }

    val targets = linkedMapOf<Pair<String, String>, Boolean>()
    suspend fun addTarget(type: String, id: String) {
      val isCol = if (type == "SALE") {
        dao.getAllSales().firstOrNull { it.id == id }?.let { it.mode == "COLLECTION" || it.isCollection } == true
      } else false
      targets[type to id] = isCol
    }
    addTarget(item.entityType, item.id)

    if (groupId != null) {
      allDeletions.filter { it.groupId == groupId }.forEach { t ->
        val recId = t.recordId.ifBlank { t.id }
        when (t.recordType.trim().lowercase()) {
          "stock_transfer" -> dao.getAllStockTransfers().firstOrNull { it.id == recId || it.outDocId == recId || it.inDocId == recId }?.let {
            addTarget("STOCK_TRANSFER", it.id)
          }
          "payment_transfer" -> dao.getAllPaymentTransfers().firstOrNull { it.id == recId || it.outDocId == recId || it.inDocId == recId }?.let {
            addTarget("PAYMENT_TRANSFER", it.id)
          }
          else -> entityTypeForTombstone(t)?.let { et -> addTarget(et, recId) }
        }
      }
    }
    return targets
  }

  private suspend fun preValidateRestoreTargets(
    ordered: List<Pair<String, String>>,
    targetKeys: Set<Pair<String, String>>,
  ) {
    val allSettlementsPre = dao.getAllSettlements()
    val allSalesPre = dao.getAllSales()
    val allProdPre = dao.getAllProductionEntries()
    val allBatchesPre = dao.getAllFactoryBatches()
    val activeFormulasPre = dao.getAllFormulas().filter { !it.isDeleted }.toMutableList()
    val restoredFormulaIds = ordered.filter { it.first == "FORMULA" }.map { it.second }.toSet()
    val allMatsPre = dao.getAllRawMaterials().filter { !it.isDeleted }.associateBy { it.id }.toMutableMap()
    dao.getAllRawMaterials().filter { it.isDeleted && ("MATERIAL" to it.id) in targetKeys }.forEach {
      allMatsPre[it.id] = it.copy(isDeleted = false)
    }
    dao.getAllFormulas().filter { it.isDeleted && it.id in restoredFormulaIds }.forEach {
      activeFormulasPre += it.copy(isDeleted = false)
    }

    for ((type, id) in ordered) {
      when (type) {
        "SALE" -> {
          val s = allSalesPre.firstOrNull { it.id == id } ?: continue
          val calcOwner = allSettlementsPre.firstOrNull { it.transferSaleId == s.id }
          if (calcOwner != null && ("SETTLEMENT" to calcOwner.id) !in targetKeys) {
            throw IllegalStateException("This rep transfer belongs to Calculator settlement for ${calcOwner.repName}. Restore that settlement instead.")
          }
        }
        "PRODUCTION" -> {
          val prod = allProdPre.firstOrNull { it.id == id } ?: continue
          val calcOwner = allSettlementsPre.firstOrNull { it.returnEntryId == prod.id }
          if (calcOwner != null && ("SETTLEMENT" to calcOwner.id) !in targetKeys) {
            throw IllegalStateException("This return entry belongs to Calculator settlement for ${calcOwner.repName}. Restore that settlement instead.")
          }
          if (!prod.isReturn && prod.formulaUnitsUsed > 0.0 && prod.formulaId.isNotBlank()) {
            val f = activeFormulasPre.firstOrNull { it.id == prod.formulaId }
              ?: throw IllegalStateException("Cannot restore production: linked formula (${prod.formulaName}) is missing or deleted.")
            if (f.unitsAvailable + 1e-6 < prod.formulaUnitsUsed) {
              throw IllegalStateException(
                "Cannot restore production: ${SarimLinkEngine.round2(prod.formulaUnitsUsed)} units of ${f.name} required, only ${SarimLinkEngine.round2(f.unitsAvailable)} available."
              )
            }
          }
        }
        "BATCH" -> {
          val b = allBatchesPre.firstOrNull { it.id == id } ?: continue
          val matsUsed = JsonCodecs.decodeFormulaMaterialsUsed(b.materialsUsedJson)
          matsUsed.forEach { used ->
            val mat = allMatsPre[used.id]
              ?: throw IllegalStateException("Cannot restore batch: raw material ${used.name} is missing or deleted.")
            if (mat.stockKg + 1e-6 < used.quantity) {
              throw IllegalStateException(
                "Cannot restore batch: insufficient ${mat.name} stock (need ${SarimLinkEngine.round2(used.quantity)} kg, available ${SarimLinkEngine.round2(mat.stockKg)} kg)."
              )
            }
          }
        }
      }
    }
  }

  private suspend fun restoreSingleRecycleTarget(
    type: String,
    id: String,
    targetIdsSet: Set<String>,
    now: Long,
    state: RestoreState,
  ) {
    when (type) {
      "STORE" -> {
        dao.restoreStore(id)
        touchTimestampKey("stores_timestamp", SarimWebSchema.COL_STORES, now)
      }
      "CUSTOMER" -> {
        dao.restoreCustomer(id)
        dao.getAllCustomers().firstOrNull { it.id == id }?.let { c ->
          val col = if (c.isRepCustomer) SarimWebSchema.COL_CLIENTS else SarimWebSchema.COL_CUSTOMERS
          enqueueOutboxSet(col, c.id, SarimWebSchema.customerToMap(c.copy(updatedAt = now)), now)
        }
      }
      "ENTITY" -> {
        dao.restorePaymentEntity(id)
        dao.getAllPaymentEntities().firstOrNull { it.id == id }?.let { e ->
          enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, e.id, SarimWebSchema.entityToMap(e.copy(updatedAt = now)), now)
        }
      }
      "MATERIAL" -> {
        dao.restoreRawMaterial(id)
        dao.getAllRawMaterials().firstOrNull { it.id == id }?.let { m ->
          enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, m.id, SarimWebSchema.materialToMap(m.copy(updatedAt = now)), now)
        }
      }
      "FORMULA" -> {
        dao.restoreFormula(id)
        state.touchedFormulas = true
      }
      "PURCHASE_LOT" -> restorePurchaseLotItem(id, now)
      "BATCH" -> {
        if (restoreBatchItem(id, now)) state.touchedFormulas = true
      }
      "PRODUCTION" -> restoreProductionItem(id, now, state)
      "RETURN" -> {
        dao.restoreReturnLog(id)
        dao.getAllReturns().firstOrNull { it.id == id }?.let { ret ->
          enqueueOutboxSet(SarimWebSchema.COL_RETURNS, ret.id, SarimWebSchema.returnLogToMap(ret.copy(updatedAt = now)), now)
        }
      }
      "EXPENSE" -> {
        dao.restoreExpense(id)
        dao.getAllExpenses().firstOrNull { it.id == id }?.let { exp ->
          enqueueOutboxSet(SarimWebSchema.COL_EXPENSES, exp.id, SarimWebSchema.expenseToMap(exp.copy(updatedAt = now)), now)
        }
      }
      "SALE" -> restoreSaleItem(id, now, state)
      "PAYMENT" -> restorePaymentItem(id, now, state)
      "LEDGER" -> {
        dao.restoreLedgerEntry(id)
        state.touchedLedger = true
      }
      "STOCK_TRANSFER" -> restoreStockTransferItem(id, now)
      "PAYMENT_TRANSFER" -> restorePaymentTransferItem(id, now)
      "SETTLEMENT" -> restoreSettlementItem(id, targetIdsSet, now, state)
    }
  }

  private suspend fun restorePurchaseLotItem(id: String, now: Long) {
    val lot = dao.getAllPurchases().firstOrNull { it.id == id }
    dao.restorePurchase(id)
    if (lot == null) return
    dao.getAllRawMaterials().firstOrNull { !it.isDeleted && it.id == lot.materialId }?.let { mat ->
      val newStock = mat.stockKg + lot.totalKg
      val newCollected = mat.collectedKg + lot.totalKg
      val newAvg = if (newStock > 0.0001) ((mat.stockKg * mat.avgCostPerKg) + lot.totalAmount) / newStock else mat.avgCostPerKg
      val newPending = mat.pendingSupplierAmount + lot.totalPayable
      val updatedMat = mat.copy(
        stockKg = newStock,
        collectedKg = newCollected,
        avgCostPerKg = newAvg,
        pendingSupplierAmount = newPending,
        paymentStatus = if (newPending > 0.001) "pending" else "paid",
        syncPending = true,
        updatedAt = now,
      )
      dao.upsertRawMaterial(updatedMat)
      enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updatedMat.id, SarimWebSchema.materialToMap(updatedMat), now)
    }
    if (!lot.supplierId.isNullOrBlank() && lot.totalPayable > 0.0) {
      dao.getAllPaymentEntities().firstOrNull { !it.isDeleted && it.id == lot.supplierId }?.let { sup ->
        val updatedSup = sup.copy(currentBalance = sup.currentBalance + lot.totalPayable, syncPending = true, updatedAt = now)
        dao.upsertPaymentEntity(updatedSup)
        enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, updatedSup.id, SarimWebSchema.entityToMap(updatedSup), now)
      }
    }
    enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, lot.id, SarimWebSchema.purchaseLotToMap(lot.copy(isDeleted = false, deletedAt = null, updatedAt = now)), now)
  }

  private suspend fun restoreBatchItem(id: String, now: Long): Boolean {
    val batch = dao.getAllFactoryBatches().firstOrNull { it.id == id }
    dao.restoreFactoryBatch(id)
    if (batch == null) return false
    var touchedFormula = false
    val allMatsNow = dao.getAllRawMaterials().filter { !it.isDeleted }.associateBy { it.id }
    val matsUsed = JsonCodecs.decodeFormulaMaterialsUsed(batch.materialsUsedJson)
    matsUsed.forEach { used ->
      allMatsNow[used.id]?.let { mat ->
        val updatedMat = mat.copy(
          stockKg = (mat.stockKg - used.quantity).coerceAtLeast(0.0),
          syncPending = true,
          updatedAt = now,
        )
        dao.upsertRawMaterial(updatedMat)
        enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updatedMat.id, SarimWebSchema.materialToMap(updatedMat), now)
      }
    }
    dao.getAllFormulas().firstOrNull { !it.isDeleted && it.id == batch.formulaId }?.let { f ->
      dao.upsertFormula(f.copy(unitsAvailable = f.unitsAvailable + batch.unitsProduced, syncPending = true, updatedAt = now))
      touchedFormula = true
    }
    enqueueOutboxSet(SarimWebSchema.COL_FACTORY, batch.id, SarimWebSchema.factoryBatchToMap(batch.copy(isDeleted = false, deletedAt = null, updatedAt = now)), now)
    return touchedFormula
  }

  private suspend fun restoreProductionItem(id: String, now: Long, state: RestoreState) {
    val prod = dao.getAllProductionEntries().firstOrNull { it.id == id }
    dao.restoreProductionEntry(id)
    if (prod == null) return
    if (!prod.isReturn && prod.formulaUnitsUsed > 0.0 && prod.formulaId.isNotBlank()) {
      dao.getAllFormulas().firstOrNull { !it.isDeleted && it.id == prod.formulaId }?.let { f ->
        dao.upsertFormula(
          f.copy(
            unitsAvailable = (f.unitsAvailable - prod.formulaUnitsUsed).coerceAtLeast(0.0),
            unitsUsed = f.unitsUsed + prod.formulaUnitsUsed,
            syncPending = true,
            updatedAt = now,
          )
        )
        state.touchedFormulas = true
      }
    }
    if (!prod.isReturn) {
      state.repricingDays += prod.storeKey to prod.dateStr.ifBlank { SarimFormat.dateInput(prod.dateEpoch) }
    }
    enqueueOutboxSet(SarimWebSchema.COL_PRODUCTION, prod.id, SarimWebSchema.productionToMap(prod.copy(isDeleted = false, deletedAt = null, updatedAt = now)), now)
  }

  private suspend fun restoreSaleItem(id: String, now: Long, state: RestoreState) {
    val sale = dao.getAllSales().firstOrNull { it.id == id }
    dao.restoreSale(id)
    if (sale == null) return
    val saleCol = if (sale.isRepSale) SarimWebSchema.COL_REP else SarimWebSchema.COL_SALES
    var finalSale = sale.copy(isDeleted = false, deletedAt = null, syncPending = true, updatedAt = now)

    if (sale.mode == "COLLECTION" || sale.isCollection) {
      val origAllocs = JsonCodecs.decodeAllocations(sale.allocationsJson)
      if (origAllocs.isNotEmpty()) {
        val activeSalesMap = dao.getAllSales().filter { !it.isDeleted }.associateBy { it.id }
        val validAllocs = mutableListOf<JsonCodecs.AllocationItem>()
        origAllocs.forEach { alloc ->
          val parent = activeSalesMap[alloc.saleId]
          if (parent != null && (parent.mode == "OLD_DEBT" || parent.transactionType == "OLD_DEBT" || (parent.mode == "SALE" && parent.paymentType == "CREDIT"))) {
            val parentAllocs = JsonCodecs.decodeCollectionAllocs(parent.collectionAllocsJson).toMutableList()
            if (parentAllocs.none { it.cid == sale.id }) {
              val newPartial = SarimLinkEngine.round2((parent.partialPaymentReceived + alloc.amount).coerceAtMost(parent.totalValue))
              val full = newPartial >= parent.totalValue - 0.01
              parentAllocs += JsonCodecs.CollectionAllocItem(cid = sale.id, amount = alloc.amount, full = full)
              val updParent = parent.copy(
                partialPaymentReceived = newPartial,
                creditReceived = full,
                collectionAllocsJson = JsonCodecs.encodeCollectionAllocs(parentAllocs),
                syncPending = true,
                updatedAt = now,
              )
              dao.upsertSale(updParent)
              enqueueOutboxSet(saleCol, updParent.id, SarimWebSchema.saleToMap(updParent), now)
            }
            validAllocs += alloc
          }
        }
        if (validAllocs.size != origAllocs.size) {
          val cashCollected = sale.collectedAmount.takeIf { it > 0.0 } ?: sale.amountReceived
          val totalAlloc = validAllocs.sumOf { it.amount }
          val cashPaid = minOf(cashCollected, totalAlloc)
          val cashLeft = SarimLinkEngine.round2((cashCollected - cashPaid).coerceAtLeast(0.0))
          finalSale = finalSale.copy(
            allocationsJson = JsonCodecs.encodeAllocations(validAllocs),
            totalValue = if (validAllocs.isNotEmpty()) cashLeft else cashCollected,
            expiredAllocated = SarimLinkEngine.round2((totalAlloc - cashPaid).coerceAtLeast(0.0)),
          )
          dao.upsertSale(finalSale)
        }
      }
      if (sale.expiredQtyKg > 0.0 && !sale.isRepReturn) {
        dao.getAllRawMaterials().firstOrNull { !it.isDeleted && it.name.equals("CHORA", ignoreCase = true) }?.let { chora ->
          val updChora = chora.copy(
            stockKg = chora.stockKg + sale.expiredQtyKg,
            collectedKg = chora.collectedKg + sale.expiredQtyKg,
            syncPending = true,
            updatedAt = now,
          )
          dao.upsertRawMaterial(updChora)
          enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updChora.id, SarimWebSchema.materialToMap(updChora), now)
        }
      }
    }

    if (!SarimLinkEngine.isRepLinkedAdminSale(finalSale) && !finalSale.isRepReturn) {
      val cashCollected = if (finalSale.collectedAmount > 0.0) finalSale.collectedAmount else finalSale.amountReceived
      dao.getAllCustomers().firstOrNull {
        !it.isDeleted && it.isRepCustomer == finalSale.isRepSale &&
          (it.id == finalSale.customerId || it.name.equals(finalSale.customerName, ignoreCase = true))
      }?.let { cust ->
        val creditDelta = when (finalSale.mode) {
          "SALE" -> if (finalSale.paymentType == "CREDIT") finalSale.totalValue else 0.0
          "COLLECTION" -> -(cashCollected + finalSale.expiredValue)
          "MONEY_OUT" -> finalSale.totalValue.takeIf { it > 0.0 } ?: cashCollected
          "OLD_DEBT" -> finalSale.totalValue
          else -> 0.0
        }
        val qtyDelta = if (finalSale.mode == "SALE") finalSale.quantityKg else 0.0
        val valDelta = if (finalSale.mode == "SALE") finalSale.totalValue else 0.0
        val recDelta = when {
          finalSale.mode == "COLLECTION" -> cashCollected
          finalSale.mode == "SALE" && finalSale.paymentType == "CASH" -> finalSale.totalValue
          else -> 0.0
        }
        val updatedCust = cust.copy(
          currentCredit = cust.currentCredit + creditDelta,
          totalQuantityKg = cust.totalQuantityKg + qtyDelta,
          totalSaleValue = cust.totalSaleValue + valDelta,
          totalReceived = cust.totalReceived + recDelta,
          syncPending = true,
          updatedAt = now,
        )
        dao.upsertCustomer(updatedCust)
        val custCol = if (updatedCust.isRepCustomer) SarimWebSchema.COL_CLIENTS else SarimWebSchema.COL_CUSTOMERS
        enqueueOutboxSet(custCol, updatedCust.id, SarimWebSchema.customerToMap(updatedCust), now)
      }
    }

    finalSale.ledgerId?.takeIf { it.isNotBlank() && finalSale.addToLedger }?.let { lid ->
      if (dao.getAllDailyLedger().any { it.id == lid && it.isDeleted }) {
        dao.restoreLedgerEntry(lid)
        state.touchedLedger = true
      }
    }
    enqueueOutboxSet(saleCol, finalSale.id, SarimWebSchema.saleToMap(finalSale), now)
  }

  private suspend fun restorePaymentItem(id: String, now: Long, state: RestoreState) {
    val tx = dao.getAllPaymentTransactions().firstOrNull { it.id == id }
    dao.restorePaymentTransaction(id)
    if (tx == null) return
    tx.expenseId?.let { expId ->
      dao.restoreExpense(expId)
      dao.getAllExpenses().firstOrNull { it.id == expId }?.let { exp ->
        enqueueOutboxSet(SarimWebSchema.COL_EXPENSES, exp.id, SarimWebSchema.expenseToMap(exp.copy(updatedAt = now)), now)
      }
      removeTombstoneAndEnqueueCloudDelete(expId, now)
    }
    if (!tx.entityId.isNullOrBlank()) {
      dao.getAllPaymentEntities().firstOrNull { !it.isDeleted && it.id == tx.entityId }?.let { ent ->
        val balDelta = if (tx.type == "PAYMENT_IN") tx.amount else -tx.amount
        val updEnt = ent.copy(currentBalance = ent.currentBalance + balDelta, syncPending = true, updatedAt = now)
        dao.upsertPaymentEntity(updEnt)
        enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, updEnt.id, SarimWebSchema.entityToMap(updEnt), now)
      }
    }
    val consumed = JsonCodecs.decodeConsumedCredits(tx.consumedCreditsJson)
    var consumedTotal = 0.0
    if (consumed.isNotEmpty()) {
      val allPay = dao.getAllPaymentTransactions()
      consumed.forEach { cItem ->
        allPay.firstOrNull { !it.isDeleted && it.id == cItem.id }?.let { inTx ->
          val take = if (cItem.amount > 0.0) cItem.amount else tx.amount
          consumedTotal += take
          val updIn = inTx.copy(
            supplierCreditAmount = (inTx.supplierCreditAmount - take).coerceAtLeast(0.0),
            syncPending = true,
            updatedAt = now,
          )
          dao.upsertPaymentTransaction(updIn)
          enqueueOutboxSet(SarimWebSchema.COL_TRANSACTIONS, updIn.id, SarimWebSchema.transactionToMap(updIn), now)
        }
      }
    }
    val payableAmt = (tx.amount - consumedTotal).coerceAtLeast(0.0)
    if (tx.isPayable && tx.type == "PAYMENT_OUT" && payableAmt > 0.001) {
      var rem = payableAmt
      val matIds = (JsonCodecs.decodeStrings(tx.materialIdsJson) + listOfNotNull(tx.materialId)).distinct()
      val lots = dao.getAllPurchases()
        .filter { !it.isDeleted && it.totalPayable > 0.001 && (it.materialId in matIds || (!tx.entityId.isNullOrBlank() && it.supplierId == tx.entityId)) }
        .sortedBy { it.dateEpoch }
      val matDeltas = linkedMapOf<String, Double>()
      for (lot in lots) {
        if (rem <= 0.001) break
        val take = minOf(rem, lot.totalPayable)
        rem -= take
        val left = (lot.totalPayable - take).coerceAtLeast(0.0)
        val updLot = lot.copy(
          totalPayable = left,
          paymentStatus = if (left <= 0.001) "paid" else "pending",
          syncPending = true,
          updatedAt = now,
        )
        dao.upsertPurchase(updLot)
        enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updLot.id, SarimWebSchema.purchaseLotToMap(updLot), now)
        matDeltas[lot.materialId] = (matDeltas[lot.materialId] ?: 0.0) + take
      }
      if (rem > 0.001 && matIds.isNotEmpty()) {
        matDeltas[matIds.first()] = (matDeltas[matIds.first()] ?: 0.0) + rem
      }
      matDeltas.forEach { (matId, delta) ->
        if (delta <= 0.001) return@forEach
        dao.getAllRawMaterials().firstOrNull { !it.isDeleted && it.id == matId }?.let { mat ->
          val left = (mat.pendingSupplierAmount - delta).coerceAtLeast(0.0)
          val updMat = mat.copy(
            pendingSupplierAmount = left,
            paymentStatus = if (left <= 0.001) "paid" else "pending",
            syncPending = true,
            updatedAt = now,
          )
          dao.upsertRawMaterial(updMat)
          enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updMat.id, SarimWebSchema.materialToMap(updMat), now)
        }
      }
    }
    tx.ledgerId?.takeIf { it.isNotBlank() && tx.addToLedger }?.let { lid ->
      if (dao.getAllDailyLedger().any { it.id == lid && it.isDeleted }) {
        dao.restoreLedgerEntry(lid)
        state.touchedLedger = true
      }
    }
    enqueueOutboxSet(SarimWebSchema.COL_TRANSACTIONS, tx.id, SarimWebSchema.transactionToMap(tx.copy(isDeleted = false, deletedAt = null, updatedAt = now)), now)
  }

  private suspend fun restoreStockTransferItem(id: String, now: Long) {
    dao.restoreStockTransfer(id)
    dao.getAllStockTransfers().firstOrNull { it.id == id }?.let { tr ->
      removeTombstoneAndEnqueueCloudDelete(tr.outDocId, now)
      removeTombstoneAndEnqueueCloudDelete(tr.inDocId, now)
      val (o, i) = SarimWebSchema.stockTransferToOutInMaps(tr.copy(isDeleted = false, deletedAt = null, updatedAt = now))
      enqueueOutboxSet(SarimWebSchema.COL_PRODUCTION, o.first, o.second, now)
      enqueueOutboxSet(SarimWebSchema.COL_PRODUCTION, i.first, i.second, now)
    }
  }

  private suspend fun restorePaymentTransferItem(id: String, now: Long) {
    dao.restorePaymentTransfer(id)
    dao.getAllPaymentTransfers().firstOrNull { it.id == id }?.let { tr ->
      removeTombstoneAndEnqueueCloudDelete(tr.outDocId, now)
      removeTombstoneAndEnqueueCloudDelete(tr.inDocId, now)
      dao.getAllPaymentEntities().firstOrNull { !it.isDeleted && it.id == tr.fromEntityId }?.let { fromEnt ->
        val upd = fromEnt.copy(currentBalance = fromEnt.currentBalance - tr.amount, syncPending = true, updatedAt = now)
        dao.upsertPaymentEntity(upd)
        enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, upd.id, SarimWebSchema.entityToMap(upd), now)
      }
      dao.getAllPaymentEntities().firstOrNull { !it.isDeleted && it.id == tr.toEntityId }?.let { toEnt ->
        val upd = toEnt.copy(currentBalance = toEnt.currentBalance + tr.amount, syncPending = true, updatedAt = now)
        dao.upsertPaymentEntity(upd)
        enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, upd.id, SarimWebSchema.entityToMap(upd), now)
      }
      val (o, i) = SarimWebSchema.paymentTransferToOutInMaps(tr.copy(isDeleted = false, deletedAt = null, updatedAt = now))
      enqueueOutboxSet(SarimWebSchema.COL_TRANSACTIONS, o.first, o.second, now)
      enqueueOutboxSet(SarimWebSchema.COL_TRANSACTIONS, i.first, i.second, now)
    }
  }

  private suspend fun restoreSettlementItem(
    id: String,
    targetIdsSet: Set<String>,
    now: Long,
    state: RestoreState,
  ) {
    val s = dao.getAllSettlements().firstOrNull { it.id == id }
    dao.restoreSettlement(id)
    if (s == null) return
    s.returnLogId?.takeIf { it.isNotBlank() && it !in targetIdsSet }?.let { rLogId ->
      dao.restoreReturnLog(rLogId)
      dao.getAllReturns().firstOrNull { it.id == rLogId }?.let { log ->
        enqueueOutboxSet(SarimWebSchema.COL_RETURNS, log.id, SarimWebSchema.returnLogToMap(log.copy(updatedAt = now)), now)
      }
      removeTombstoneAndEnqueueCloudDelete(rLogId, now)
    }
    s.returnEntryId?.takeIf { it.isNotBlank() && it !in targetIdsSet }?.let { rEntryId ->
      dao.restoreProductionEntry(rEntryId)
      dao.getAllProductionEntries().firstOrNull { it.id == rEntryId }?.let { prod ->
        enqueueOutboxSet(SarimWebSchema.COL_PRODUCTION, prod.id, SarimWebSchema.productionToMap(prod.copy(updatedAt = now)), now)
      }
      removeTombstoneAndEnqueueCloudDelete(rEntryId, now)
    }
    s.transferSaleId?.takeIf { it.isNotBlank() && it !in targetIdsSet }?.let { tSaleId ->
      dao.restoreSale(tSaleId)
      dao.getAllSales().firstOrNull { it.id == tSaleId }?.let { trSale ->
        enqueueOutboxSet(SarimWebSchema.COL_SALES, trSale.id, SarimWebSchema.saleToMap(trSale.copy(updatedAt = now)), now)
      }
      removeTombstoneAndEnqueueCloudDelete(tSaleId, now)
    }

    val linkedSalesIds = JsonCodecs.decodeStrings(s.linkedSalesIdsJson).toSet()
    val linkedRepSalesIds = JsonCodecs.decodeStrings(s.linkedRepSalesIdsJson).toSet()
    dao.getAllSales().filter { !it.isDeleted }.forEach { sale ->
      if (!sale.isRepSale && sale.id in linkedSalesIds) {
        val relinked = sale.copy(creditReceived = true, usedInCalcId = s.id, syncPending = true, updatedAt = now)
        dao.upsertSale(relinked)
        enqueueOutboxSet(SarimWebSchema.COL_SALES, relinked.id, SarimWebSchema.saleToMap(relinked), now)
      } else if (sale.isRepSale && sale.id in linkedRepSalesIds) {
        val relinked = sale.copy(usedInCalcId = s.id, syncPending = true, updatedAt = now)
        dao.upsertSale(relinked)
        enqueueOutboxSet(SarimWebSchema.COL_REP, relinked.id, SarimWebSchema.saleToMap(relinked), now)
      }
    }

    if (s.expiredKg > 0.0 && s.expiredApplied) {
      dao.getAllRawMaterials().firstOrNull { !it.isDeleted && it.name.equals("CHORA", ignoreCase = true) }?.let { chora ->
        val updChora = chora.copy(
          stockKg = chora.stockKg + s.expiredKg,
          collectedKg = chora.collectedKg + s.expiredKg,
          syncPending = true,
          updatedAt = now,
        )
        dao.upsertRawMaterial(updChora)
        enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updChora.id, SarimWebSchema.materialToMap(updChora), now)
      }
    }

    if (s.addToLedger) {
      dao.getAllDailyLedger().filter { it.isDeleted && it.sourceType == "CALCULATOR" && it.sourceId == s.id }.forEach { l ->
        dao.restoreLedgerEntry(l.id)
        state.touchedLedger = true
      }
    }
    enqueueOutboxSet(SarimWebSchema.COL_CALCULATOR, s.id, SarimWebSchema.settlementToMap(s.copy(isDeleted = false, deletedAt = null, updatedAt = now)), now)
  }

  private suspend fun repriceRestoredProductionDays(
    repricingDays: Set<Pair<String, String>>,
    now: Long,
  ) {
    val allProd = dao.getAllProductionEntries().filter { !it.isDeleted && !it.isReturn }
    val allSales = dao.getAllSales().filter { !it.isDeleted && !it.isRepSale && it.mode == "SALE" }
    repricingDays.forEach { (storeKey, dateStr) ->
      val dayProd = allProd.filter {
        it.storeKey == storeKey && (it.dateStr.ifBlank { SarimFormat.dateInput(it.dateEpoch) } == dateStr)
      }
      val totalKg = dayProd.sumOf { it.netWeightKg }
      val totalCost = dayProd.sumOf { it.totalFormulaCost }
      if (totalKg > 0.0001) {
        val avgCostPerKg = SarimLinkEngine.round2(totalCost / totalKg)
        allSales.filter { s ->
          s.storeKey == storeKey && (s.dateStr.ifBlank { SarimFormat.dateInput(s.dateEpoch) } == dateStr) && !s.isRepTransfer
        }.forEach { sale ->
          val newCost = SarimLinkEngine.round2(sale.quantityKg * avgCostPerKg)
          val newProfit = SarimLinkEngine.round2(sale.totalValue - newCost)
          if (kotlin.math.abs(sale.totalCost - newCost) > 0.05 || kotlin.math.abs(sale.profit - newProfit) > 0.05) {
            val upd = sale.copy(totalCost = newCost, profit = newProfit, syncPending = true, updatedAt = now)
            dao.upsertSale(upd)
            enqueueOutboxSet(SarimWebSchema.COL_SALES, upd.id, SarimWebSchema.saleToMap(upd), now)
          }
        }
      }
    }
  }
}
