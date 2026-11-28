package com.example.data.repository

import com.example.data.local.*
import com.example.data.model.EditHistoryChange
import com.example.data.model.EditHistoryEntry
import com.example.data.model.FormulaIngredient
import com.example.data.model.SarimUuid
import com.example.data.remote.SarimWebSchema
import com.example.data.remote.UuidSyncRegistry
import com.example.data.repository.SarimLinkEngine.QuadrupleDiff
import com.example.util.SarimFormat
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

class SarimRepository(
  private val dao: SarimDao,
  private val safeReader: SafeSqliteCacheReader = SafeSqliteCacheReader(dao),
  private val uuidRegistry: UuidSyncRegistry? = null,
) {
  val stores: Flow<List<StoreEntity>> = dao.observeStores()
  val rawMaterials: Flow<List<RawMaterialEntity>> = dao.observeRawMaterials()
  val purchases: Flow<List<RawMaterialPurchaseEntity>> = dao.observePurchases()
  val formulas: Flow<List<FormulaEntity>> = dao.observeFormulas()
  val factoryBatches: Flow<List<FactoryBatchEntity>> = dao.observeFactoryBatches()
  val productionEntries: Flow<List<ProductionEntryEntity>> = dao.observeProductionEntries()
  val customers: Flow<List<CustomerEntity>> = dao.observeCustomers()
  val sales: Flow<List<SaleTransactionEntity>> = dao.observeSales()
  val paymentEntities: Flow<List<PaymentEntityRecord>> = dao.observePaymentEntities()
  val paymentTransactions: Flow<List<PaymentTransactionEntity>> = dao.observePaymentTransactions()
  val expenses: Flow<List<ExpenseRecordEntity>> = dao.observeExpenses()
  val returns: Flow<List<ReturnLogEntity>> = dao.observeReturns()
  val dailyLedger: Flow<List<DailyLedgerEntity>> = dao.observeDailyLedger()
  val settlements: Flow<List<CalculatorSettlementEntity>> = dao.observeSettlements()
  val stockTransfers: Flow<List<StockTransferEntity>> = dao.observeStockTransfers()
  val paymentTransfers: Flow<List<PaymentTransferEntity>> = dao.observePaymentTransfers()
  val mixCalcs: Flow<List<SavedMixCalcEntity>> = dao.observeMixCalcs()
  val approvals: Flow<List<ApprovalRequestEntity>> = dao.observeApprovals()
  val accounts: Flow<List<AppAccountEntity>> = dao.observeAccounts()
  val allKvKeys: Flow<List<String>> = dao.observeAllKvKeys()
  val categoryAndRepKv: Flow<List<KvStoreEntity>> = dao.observeCategoryAndRepKv()

  suspend fun initDefaults() {
    SarimSeedHelper.ensureStructuralDefaults(dao)
  }

  private fun mapToJsonString(map: Map<String, Any?>): String = JsonCodecs.encodeMap(map)

  private suspend fun enqueueOutboxSet(collection: String, docId: String, payloadMap: Map<String, Any?>) {
    val now = System.currentTimeMillis()
    val json = mapToJsonString(payloadMap)
    dao.removeOutboxForDoc(collection, docId)
    dao.insertOutbox(
      NdappOutboxEntity(
        id = SarimUuid.generateUUID(SarimUuid.PREFIX_OB, tsMs = now),
        action = "set",
        collection = collection,
        docId = docId,
        payload = json,
        createdAt = now,
      )
    )
    uuidRegistry?.markLocalModified(collection, docId, now)
    touchTimestampKey("${collection}_timestamp", collection, now)
  }

  private fun buildSafeSnapshotJson(snapshotMap: Map<String, Any?>?): String? {
    if (snapshotMap == null) return null
    val raw = mapToJsonString(snapshotMap)
    if (raw.length <= SAFE_SNAPSHOT_MAX_CHARS) return raw
    val trimmed = snapshotMap.filterKeys { k ->
      k !in setOf("photoKeys", "photoUris", "data", "editHistory", "allocations", "collectionAllocs")
    }
    val compact = mapToJsonString(trimmed)
    return if (compact.length <= SAFE_SNAPSHOT_MAX_CHARS) compact else compact.take(SAFE_SNAPSHOT_MAX_CHARS)
  }

  private suspend fun enqueueOutboxDelete(
    collection: String,
    docId: String,
    title: String,
    subtitle: String,
    amountText: String,
    snapshotMap: Map<String, Any?>? = null,
    groupId: String? = null,
    recordType: String = collection,
  ) {
    val now = System.currentTimeMillis()
    dao.removeOutboxForDoc(collection, docId)
    dao.insertOutbox(
      NdappOutboxEntity(
        id = SarimUuid.generateUUID(SarimUuid.PREFIX_OB, tsMs = now),
        action = "delete",
        collection = collection,
        docId = docId,
        payload = null,
        createdAt = now,
      )
    )
    dao.upsertDeletion(
      DeletionTombstoneEntity(
        id = docId,
        recordId = docId,
        collection = collection,
        recordType = recordType,
        deletedAt = now,
        expiresAt = now + SarimWebSchema.TOMBSTONE_EXPIRY_MS,
        syncedToCloud = false,
        displayName = title,
        displayDetail = subtitle,
        displayAmount = amountText,
        snapshotJson = buildSafeSnapshotJson(snapshotMap),
        deletedBy = "Admin",
        deletionVersion = "2.0",
        groupId = groupId,
      )
    )
    uuidRegistry?.markLocalModified(collection, docId, now)
    touchTimestampKey("${collection}_timestamp", collection, now)
  }

  private suspend fun recordSingletonDeletion(
    collection: String,
    docId: String,
    title: String,
    subtitle: String,
    amountText: String,
    snapshotMap: Map<String, Any?>? = null,
    groupId: String? = null,
    recordType: String = collection,
  ) {
    val now = System.currentTimeMillis()
    dao.upsertDeletion(
      DeletionTombstoneEntity(
        id = docId,
        recordId = docId,
        collection = collection,
        recordType = recordType,
        deletedAt = now,
        expiresAt = now + SarimWebSchema.TOMBSTONE_EXPIRY_MS,
        syncedToCloud = false,
        displayName = title,
        displayDetail = subtitle,
        displayAmount = amountText,
        snapshotJson = buildSafeSnapshotJson(snapshotMap),
        deletedBy = "Admin",
        deletionVersion = "2.0",
        groupId = groupId,
      )
    )
    touchTimestampKey("${collection}_timestamp", collection, now)
  }

  private suspend fun touchTimestampKey(userKey: String, collection: String = "", now: Long = System.currentTimeMillis()) {
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
    if (collection.isNotBlank()) {
      uuidRegistry?.markLocalModified(collection, null, now)
    }
  }

  private suspend fun upsertPhotoKey(photoKey: String, data: String, now: Long = System.currentTimeMillis()) {
    dao.upsertPhoto(
      PhotoRecordEntity(
        photoKey = photoKey,
        docId = SarimWebSchema.photoDocId(photoKey),
        data = data,
        deleted = false,
        updatedAt = now,
        syncPending = true,
      )
    )
  }

  private suspend fun markPhotoDeleted(photoKey: String, now: Long = System.currentTimeMillis()) {
    dao.upsertPhoto(
      PhotoRecordEntity(
        photoKey = photoKey,
        docId = SarimWebSchema.photoDocId(photoKey),
        data = null,
        deleted = true,
        updatedAt = now,
        syncPending = true,
      )
    )
  }

  private suspend fun appendCategoryToKv(userKey: String, categoryName: String, now: Long = System.currentTimeMillis()) {
    val clean = categoryName.trim()
    if (clean.isBlank()) return
    val existing = safeReader.getKvByUserKeyOnce(userKey)
    val list = JsonCodecs.decodeStrings(existing?.value ?: "[]").toMutableList()
    if (list.none { it.equals(clean, ignoreCase = true) }) {
      list += clean
      dao.upsertKv(
        KvStoreEntity(
          fullKey = userKey,
          userKey = userKey,
          collection = userKey,
          rowType = "settings",
          value = JsonCodecs.encodeStrings(list),
          ts = now,
          createdAt = existing?.createdAt ?: now,
        )
      )
      touchTimestampKey("${userKey}_timestamp", userKey, now)
      dao.upsertKv(
        KvStoreEntity(
          fullKey = "${userKey}_dirty",
          userKey = "${userKey}_dirty",
          collection = userKey,
          rowType = "meta",
          value = "1",
          ts = now,
          createdAt = now,
        )
      )
    }
  }

  suspend fun saveCategoryIfNew(userKey: String, categoryName: String) {
    appendCategoryToKv(userKey, categoryName)
  }

  private fun customerPhotoKey(c: CustomerEntity): String {
    val cleanName = c.name.trim().lowercase()
    return if (c.isRepCustomer) "rep-cust:${c.repName}:$cleanName" else "cust:$cleanName"
  }

  suspend fun saveStore(name: String, formulaId: String, salePrice: Double, editingKey: String? = null) {
    val cleanName = name.trim().uppercase()
    val key = editingKey ?: SarimSeedHelper.storeKeyFromName(cleanName)
    val now = System.currentTimeMillis()
    dao.upsertStore(
      StoreEntity(
        storeKey = key,
        name = cleanName,
        formulaId = formulaId,
        salePricePerKg = salePrice,
        syncPending = true,
        updatedAt = now,
      )
    )
    touchTimestampKey("stores_timestamp", SarimWebSchema.COL_STORES, now)
  }

  suspend fun deleteStore(key: String) {
    val now = System.currentTimeMillis()
    val s = dao.getAllStores().firstOrNull { it.storeKey == key }
    val usage = SarimLinkEngine.storeUsage(
      storeKey = key,
      production = dao.getAllProductionEntries(),
      factoryBatches = dao.getAllFactoryBatches(),
      sales = dao.getAllSales(),
      returns = dao.getAllReturns(),
      stockTransfers = dao.getAllStockTransfers(),
    )
    usage.blockMessage(s?.name ?: key)?.let { msg ->
      throw IllegalStateException(msg)
    }
    dao.softDeleteStore(key, now)
    val snapshot = s?.let {
      linkedMapOf<String, Any?>(
        "key" to it.storeKey,
        "name" to it.name,
        "formulaId" to it.formulaId,
        "salePrice" to it.salePricePerKg,
      )
    }
    recordSingletonDeletion(
      collection = SarimWebSchema.COL_STORES,
      docId = key,
      title = s?.name ?: key,
      subtitle = "Formula ${s?.formulaId ?: ""}",
      amountText = "Rs ${s?.salePricePerKg ?: 0.0}/kg",
      snapshotMap = snapshot,
    )
  }

  private suspend fun assertStoreRemainingNonNegative(
    storeKey: String,
    excludingProdId: String?,
    addedNetKg: Double,
  ) {
    val producedWithout = dao.getAllProductionEntries()
      .filter { !it.isDeleted && !it.isReturn && it.storeKey == storeKey && it.id != excludingProdId }
      .sumOf { it.netWeightKg }
    val tIn = dao.getAllStockTransfers().filter { !it.isDeleted && it.toStoreKey == storeKey }.sumOf { it.quantityKg }
    val tOut = dao.getAllStockTransfers().filter { !it.isDeleted && it.fromStoreKey == storeKey }.sumOf { it.quantityKg }
    val returned = dao.getAllSettlements()
      .filter { !it.isDeleted && !it.returnType.equals("TRANSFER", true) && it.returnStoreKey == storeKey }
      .sumOf { it.returnedKg }
    val sold = dao.getAllSales()
      .filter { !it.isDeleted && !it.isRepSale && !it.isRepReturn && !it.isRepTransfer && it.mode == "SALE" && it.storeKey == storeKey }
      .sumOf { it.quantityKg }
    val projectedRemaining = producedWithout + addedNetKg + tIn + returned - sold - tOut
    if (projectedRemaining < -0.001) {
      throw IllegalStateException("Remaining store stock cannot drop below zero (sold or transferred stock exceeds new remaining).")
    }
  }

  suspend fun repriceSalesForProductionDay(storeKey: String, dateStr: String, now: Long = System.currentTimeMillis()) {
    val cleanKey = storeKey.trim()
    val cleanDate = dateStr.trim()
    if (cleanKey.isBlank() || cleanDate.isBlank()) return
    val prods = dao.getAllProductionEntries().filter {
      !it.isDeleted && !it.isReturn && it.storeKey.equals(cleanKey, ignoreCase = true) &&
        it.dateStr.ifBlank { SarimFormat.dateInput(it.dateEpoch) } == cleanDate
    }
    val totNet = prods.sumOf { it.netWeightKg }
    val totCost = prods.sumOf { it.totalFormulaCost }
    val unitCostPerKg = if (totNet > 0.0001) {
      totCost / totNet
    } else {
      val store = dao.getAllStores().firstOrNull { !it.isDeleted && it.storeKey.equals(cleanKey, ignoreCase = true) }
        ?: return
      val formulas = dao.getAllFormulas().filter { !it.isDeleted }.associateBy { it.id }
      val materials = dao.getAllRawMaterials().filter { !it.isDeleted }.associateBy { it.id }
      val f = formulas[store.formulaId] ?: return
      FormulaCalculator.computeFormulaSummary(f, formulas, materials).costPerKgAdjusted
    }
    dao.getAllSales().filter { s ->
      !s.isDeleted && !s.isRepSale && !s.isRepReturn && !s.isRepTransfer &&
        s.mode == "SALE" && s.storeKey.equals(cleanKey, ignoreCase = true) &&
        s.dateStr.ifBlank { SarimFormat.dateInput(s.dateEpoch) } == cleanDate &&
        (s.costSource.isNullOrBlank() || s.costSource == "store")
    }.forEach { sale ->
      val newTotalCost = SarimLinkEngine.round2(sale.quantityKg * unitCostPerKg)
      val newProfit = SarimLinkEngine.round2(sale.totalValue - newTotalCost)
      if (kotlin.math.abs(sale.totalCost - newTotalCost) > 0.01 || kotlin.math.abs(sale.profit - newProfit) > 0.01) {
        val updated = sale.copy(
          totalCost = newTotalCost,
          profit = newProfit,
          syncPending = true,
          updatedAt = now,
        )
        dao.upsertSale(updated)
        enqueueOutboxSet(SarimWebSchema.COL_SALES, updated.id, SarimWebSchema.saleToMap(updated))
      }
    }
  }

  suspend fun saveProductionEntry(
    editingId: String?,
    dateEpoch: Long,
    store: StoreEntity,
    formula: FormulaEntity,
    grossKg: Double,
    containerKg: Double,
    unitsUsed: Double,
    unitCost: Double,
    photoUris: List<String>,
    actor: String = "Admin",
  ) {
    val now = System.currentTimeMillis()
    val existing = editingId?.let { id -> dao.getAllProductionEntries().firstOrNull { it.id == id } }
    val netKg = (grossKg - containerKg).coerceAtLeast(0.0)
    if (existing != null && !existing.isReturn && existing.storeKey != store.storeKey) {
      assertStoreRemainingNonNegative(existing.storeKey, existing.id, 0.0)
    }
    assertStoreRemainingNonNegative(store.storeKey, existing?.id, netKg)

    // Guard against drawing more formula units than available (matches web factory-prod pool rule)
    if (existing == null) {
      if (unitsUsed > formula.unitsAvailable + 1e-6) {
        throw IllegalStateException("Insufficient ${formula.name} units available (${SarimLinkEngine.round2(formula.unitsAvailable)} left, requested ${SarimLinkEngine.round2(unitsUsed)}).")
      }
    } else if (existing.formulaId == formula.id) {
      val deltaUnits = unitsUsed - existing.formulaUnitsUsed
      if (deltaUnits > formula.unitsAvailable + 1e-6) {
        throw IllegalStateException("Insufficient ${formula.name} units available for edit (${SarimLinkEngine.round2(formula.unitsAvailable)} left).")
      }
    } else {
      if (unitsUsed > formula.unitsAvailable + 1e-6) {
        throw IllegalStateException("Insufficient ${formula.name} units available (${SarimLinkEngine.round2(formula.unitsAvailable)} left, requested ${SarimLinkEngine.round2(unitsUsed)}).")
      }
    }

    val totalFormulaCost = unitsUsed * unitCost
    val costPerKg = if (netKg > 0.0001) totalFormulaCost / netKg else 0.0
    val salePrice = store.salePricePerKg
    val profitPerKg = salePrice - costPerKg
    val totalValue = netKg * salePrice
    val totalProfit = totalValue - totalFormulaCost

    if (existing == null) {
      if (unitsUsed > 0.0) {
        dao.upsertFormula(
          formula.copy(
            unitsAvailable = (formula.unitsAvailable - unitsUsed).coerceAtLeast(0.0),
            unitsUsed = formula.unitsUsed + unitsUsed,
            syncPending = true,
            updatedAt = now,
          )
        )
        touchTimestampKey("formulas_timestamp", SarimWebSchema.COL_FORMULAS, now)
      }
    } else {
      val prevUnits = existing.formulaUnitsUsed
      if (existing.formulaId == formula.id) {
        val deltaUnits = unitsUsed - prevUnits
        if (kotlin.math.abs(deltaUnits) > 0.0001) {
          dao.upsertFormula(
            formula.copy(
              unitsAvailable = (formula.unitsAvailable - deltaUnits).coerceAtLeast(0.0),
              unitsUsed = (formula.unitsUsed + deltaUnits).coerceAtLeast(0.0),
              syncPending = true,
              updatedAt = now,
            )
          )
          touchTimestampKey("formulas_timestamp", SarimWebSchema.COL_FORMULAS, now)
        }
      } else {
        dao.getAllFormulas().firstOrNull { it.id == existing.formulaId }?.let { prevF ->
          dao.upsertFormula(
            prevF.copy(
              unitsAvailable = prevF.unitsAvailable + prevUnits,
              unitsUsed = (prevF.unitsUsed - prevUnits).coerceAtLeast(0.0),
              syncPending = true,
              updatedAt = now,
            )
          )
        }
        dao.upsertFormula(
          formula.copy(
            unitsAvailable = (formula.unitsAvailable - unitsUsed).coerceAtLeast(0.0),
            unitsUsed = formula.unitsUsed + unitsUsed,
            syncPending = true,
            updatedAt = now,
          )
        )
        touchTimestampKey("formulas_timestamp", SarimWebSchema.COL_FORMULAS, now)
      }
    }

    val allMats = dao.getAllRawMaterials().filter { !it.isDeleted }.associateBy { it.id }
    val allFormulas = dao.getAllFormulas().filter { !it.isDeleted }.associateBy { it.id }
    val flatRaw = FormulaCalculator.flattenRawMaterials(formula, unitsUsed, allFormulas, allMats)
    val formulaMats = flatRaw.map { (matId, kg) ->
      val m = allMats[matId]
      JsonCodecs.FormulaMaterialUsed(
        id = matId,
        name = m?.name ?: matId,
        quantity = kg,
        cost = kg * (m?.avgCostPerKg ?: 0.0),
      )
    }

    val newDateStr = SarimFormat.dateInput(dateEpoch)
    val history = existing?.let { JsonCodecs.decodeEditHistory(it.editHistoryJson) }?.toMutableList() ?: mutableListOf()
    if (existing != null) {
      val changes = SarimLinkEngine.buildEditDiffs(
        listOf(
          QuadrupleDiff("dateStr", "Date", existing.dateStr, newDateStr),
          QuadrupleDiff("store", "Store", existing.storeName, store.name),
          QuadrupleDiff("formula", "Formula", existing.formulaName, formula.name),
          QuadrupleDiff("gross", "Gross (kg)", SarimFormat.formatKg(existing.grossWeightKg), SarimFormat.formatKg(grossKg)),
          QuadrupleDiff("container", "Container (kg)", SarimFormat.formatKg(existing.containerWeightKg), SarimFormat.formatKg(containerKg)),
          QuadrupleDiff("net", "Net (kg)", SarimFormat.formatKg(existing.netWeightKg), SarimFormat.formatKg(netKg)),
          QuadrupleDiff("units", "Formula Units", SarimLinkEngine.round2(existing.formulaUnitsUsed).toString(), SarimLinkEngine.round2(unitsUsed).toString()),
        )
      )
      history.add(0, EditHistoryEntry(now, "Updated ${store.name}: ${SarimFormat.formatKg(netKg)}", by = actor, changes = changes))
    }

    val entity = (existing ?: ProductionEntryEntity(
      dateEpoch = dateEpoch,
      storeKey = store.storeKey,
      storeName = store.name,
      formulaId = formula.id,
      formulaName = formula.name,
      grossWeightKg = grossKg,
      containerWeightKg = containerKg,
      netWeightKg = netKg,
      formulaUnitsUsed = unitsUsed,
      formulaUnitCost = unitCost,
      totalFormulaCost = totalFormulaCost,
      costPerKg = costPerKg,
      salePricePerKg = salePrice,
      profitPerKg = profitPerKg,
      totalValue = totalValue,
      totalProfit = totalProfit,
    )).copy(
      dateEpoch = dateEpoch,
      storeKey = store.storeKey,
      storeName = store.name,
      formulaId = formula.id,
      formulaName = formula.name,
      grossWeightKg = grossKg,
      containerWeightKg = containerKg,
      netWeightKg = netKg,
      formulaUnitsUsed = unitsUsed,
      formulaUnitCost = unitCost,
      totalFormulaCost = totalFormulaCost,
      costPerKg = costPerKg,
      salePricePerKg = salePrice,
      profitPerKg = profitPerKg,
      totalValue = totalValue,
      totalProfit = totalProfit,
      photoUrisJson = JsonCodecs.encodeStrings(photoUris),
      dateStr = SarimFormat.dateInput(dateEpoch),
      timeStr = existing?.timeStr?.takeIf { it.isNotBlank() } ?: SarimFormat.formatTime(now),
      formulaMaterialsJson = JsonCodecs.encodeFormulaMaterialsUsed(formulaMats),
      editHistoryJson = JsonCodecs.encodeEditHistory(history.take(100)),
      syncPending = true,
      updatedAt = now,
    )
    dao.upsertProductionEntry(entity)

    val oldPhotoCount = existing?.let { JsonCodecs.decodeStrings(it.photoUrisJson).size } ?: 0
    photoUris.forEachIndexed { idx, uri ->
      upsertPhotoKey("prod:${entity.id}:$idx", uri, now)
    }
    if (oldPhotoCount > photoUris.size) {
      for (idx in photoUris.size until oldPhotoCount) {
        markPhotoDeleted("prod:${entity.id}:$idx", now)
      }
    }

    enqueueOutboxSet(SarimWebSchema.COL_PRODUCTION, entity.id, SarimWebSchema.productionToMap(entity))
    if (!entity.isReturn) {
      repriceSalesForProductionDay(entity.storeKey, entity.dateStr, now)
      if (existing != null && (!existing.storeKey.equals(entity.storeKey, true) || existing.dateStr != entity.dateStr)) {
        repriceSalesForProductionDay(existing.storeKey, existing.dateStr, now)
      }
    }
  }

  suspend fun deleteProductionEntry(id: String) {
    val now = System.currentTimeMillis()
    val item = dao.getAllProductionEntries().firstOrNull { it.id == id }
    if (item != null) {
      if (item.isReturn) {
        val calcOwner = dao.getAllSettlements().firstOrNull { !it.isDeleted && it.returnEntryId == item.id }
        if (calcOwner != null) {
          throw IllegalStateException("This return belongs to a Calculator settlement for ${calcOwner.repName}. Delete that settlement first.")
        }
      }
      if (!item.isReturn || !item.returnLogId.isNullOrBlank()) {
        assertStoreRemainingNonNegative(item.storeKey, item.id, 0.0)
      }
    }
    val groupId = if (item?.isReturn == true && !item.returnLogId.isNullOrBlank()) {
      SarimUuid.generateUUID("${SarimUuid.PREFIX_GRP}_prod", tsMs = now)
    } else null

    if (item != null) {
      if (!item.isReturn && item.formulaUnitsUsed > 0.0 && item.formulaId.isNotBlank()) {
        dao.getAllFormulas().firstOrNull { it.id == item.formulaId }?.let { f ->
          dao.upsertFormula(
            f.copy(
              unitsAvailable = f.unitsAvailable + item.formulaUnitsUsed,
              unitsUsed = (f.unitsUsed - item.formulaUnitsUsed).coerceAtLeast(0.0),
              syncPending = true,
              updatedAt = now,
            )
          )
          touchTimestampKey("formulas_timestamp", SarimWebSchema.COL_FORMULAS, now)
        }
      }
      if (item.isReturn && !item.returnLogId.isNullOrBlank()) {
        val log = dao.getAllReturns().firstOrNull { it.id == item.returnLogId }
        dao.softDeleteReturnLog(item.returnLogId, now)
        enqueueOutboxDelete(
          collection = SarimWebSchema.COL_RETURNS,
          docId = item.returnLogId,
          title = "Return: ${log?.seller ?: item.returnedBy.orEmpty()}",
          subtitle = "${item.netWeightKg} kg -> ${item.storeName}",
          amountText = "${item.netWeightKg} kg",
          snapshotMap = log?.let { SarimWebSchema.returnLogToMap(it) },
          groupId = groupId,
        )
      }
      dao.getPhotoMetaAll()
        .filter { !it.deleted && it.photoKey.startsWith("prod:${item.id}:") }
        .forEach { p -> markPhotoDeleted(p.photoKey, now) }
    }

    dao.softDeleteProductionEntry(id, now)
    enqueueOutboxDelete(
      collection = SarimWebSchema.COL_PRODUCTION,
      docId = id,
      title = "Production: ${item?.storeName ?: id}",
      subtitle = "${item?.netWeightKg ?: 0.0} kg",
      amountText = "Rs ${item?.totalValue ?: 0.0}",
      snapshotMap = item?.let { SarimWebSchema.productionToMap(it) },
      groupId = groupId,
    )
    if (item != null && !item.isReturn) {
      repriceSalesForProductionDay(item.storeKey, item.dateStr.ifBlank { SarimFormat.dateInput(item.dateEpoch) }, now)
    }
  }

  suspend fun saveRawMaterialPurchase(
    materialName: String,
    dateEpoch: Long,
    unitName: String,
    unitsBought: Double,
    weightPerUnitKg: Double,
    costPerUnit: Double,
    supplierMode: String,
    existingSupplier: PaymentEntityRecord?,
    newSupplierName: String,
    newSupplierPhone: String,
    editingLotId: String? = null,
    adjustSupplierPayable: Boolean = true,
  ) {
    val now = System.currentTimeMillis()
    val cleanMat = materialName.trim()
    val existingLot = editingLotId?.let { id -> dao.getAllPurchases().firstOrNull { it.id == id && !it.isDeleted } }
    val totalKg = unitsBought * weightPerUnitKg
    val totalAmount = unitsBought * costPerUnit

    if (existingLot != null) {
      val oldMat = dao.getAllRawMaterials().firstOrNull { it.id == existingLot.materialId && !it.isDeleted }
      if (oldMat != null) {
        val sameMat = oldMat.name.equals(cleanMat, ignoreCase = true)
        val projectedOldMatStock = oldMat.stockKg - existingLot.totalKg + if (sameMat) totalKg else 0.0
        if (projectedOldMatStock < -1e-6) {
          throw IllegalStateException(
            "${SarimLinkEngine.round2(-projectedOldMatStock)} kg more of this purchase was already used in production than the new weight allows."
          )
        }
        val remStock = (oldMat.stockKg - existingLot.totalKg).coerceAtLeast(0.0)
        val remCollected = (oldMat.collectedKg - existingLot.totalKg).coerceAtLeast(0.0)
        val remAvg = if (remStock > 0.0001) {
          ((oldMat.stockKg * oldMat.avgCostPerKg - existingLot.totalAmount).coerceAtLeast(0.0)) / remStock
        } else 0.0
        val remPending = if (adjustSupplierPayable) {
          (oldMat.pendingSupplierAmount - existingLot.totalPayable).coerceAtLeast(0.0)
        } else oldMat.pendingSupplierAmount
        val revertedMat = oldMat.copy(
          stockKg = remStock,
          collectedKg = remCollected,
          avgCostPerKg = remAvg,
          pendingSupplierAmount = remPending,
          paymentStatus = if (remPending > 0.001) "pending" else "paid",
          syncPending = true,
          updatedAt = now,
        )
        dao.upsertRawMaterial(revertedMat)
        enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, revertedMat.id, SarimWebSchema.materialToMap(revertedMat))
      }
      if (adjustSupplierPayable && !existingLot.supplierId.isNullOrBlank() && existingLot.totalPayable > 0.0) {
        dao.getAllPaymentEntities().firstOrNull { it.id == existingLot.supplierId && !it.isDeleted }?.let { sup ->
          val revSup = sup.copy(
            currentBalance = sup.currentBalance - existingLot.totalPayable,
            syncPending = true,
            updatedAt = now,
          )
          dao.upsertPaymentEntity(revSup)
          enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, revSup.id, SarimWebSchema.entityToMap(revSup))
        }
      }
    }

    val allMats = dao.getAllRawMaterials().filter { !it.isDeleted }
    val existingMat = allMats.firstOrNull { it.name.equals(cleanMat, ignoreCase = true) }
    val prevStock = existingMat?.stockKg ?: 0.0
    val prevAvg = existingMat?.avgCostPerKg ?: 0.0
    val newStock = prevStock + totalKg
    val newAvgCost = if (newStock > 0.0001) ((prevStock * prevAvg) + totalAmount) / newStock else 0.0

    val applyPayableToLot = if (existingLot == null || adjustSupplierPayable) {
      if (supplierMode != "NONE") totalAmount else 0.0
    } else existingLot.totalPayable

    var supplierId = existingSupplier?.id
    var supplierNameFinal = existingSupplier?.name
    if (supplierMode == "NEW" && newSupplierName.isNotBlank()) {
      val newEnt = PaymentEntityRecord(
        name = newSupplierName.trim(),
        phone = newSupplierPhone.trim(),
        openingType = "CREDIT",
        openingAmount = 0.0,
        currentBalance = applyPayableToLot,
        type = "payee",
        isSupplier = true,
      )
      dao.upsertPaymentEntity(newEnt)
      enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, newEnt.id, SarimWebSchema.entityToMap(newEnt))
      supplierId = newEnt.id
      supplierNameFinal = newEnt.name
    } else if (supplierMode == "EXISTING" && existingSupplier != null) {
      val freshSup = dao.getAllPaymentEntities().firstOrNull { it.id == existingSupplier.id && !it.isDeleted } ?: existingSupplier
      val balanceDelta = if (existingLot == null || adjustSupplierPayable) applyPayableToLot else 0.0
      val updatedSup = freshSup.copy(
        currentBalance = freshSup.currentBalance + balanceDelta,
        isSupplier = true,
        syncPending = true,
        updatedAt = now,
      )
      dao.upsertPaymentEntity(updatedSup)
      enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, updatedSup.id, SarimWebSchema.entityToMap(updatedSup))
    } else if (existingLot != null && !adjustSupplierPayable) {
      supplierId = existingLot.supplierId
      supplierNameFinal = existingLot.supplierName
    }

    val addedPendingToMat = if (existingLot == null || adjustSupplierPayable) applyPayableToLot else 0.0
    val newPending = (existingMat?.pendingSupplierAmount ?: 0.0) + addedPendingToMat
    val matEntity = (existingMat ?: RawMaterialEntity(name = cleanMat)).copy(
      name = cleanMat,
      unitName = unitName.trim().ifBlank { "kg" },
      conversionFactorKg = weightPerUnitKg,
      stockKg = newStock,
      collectedKg = (existingMat?.collectedKg ?: 0.0) + totalKg,
      avgCostPerKg = newAvgCost,
      pendingSupplierAmount = newPending,
      paymentStatus = if (newPending > 0.001) "pending" else "paid",
      supplierId = supplierId ?: existingMat?.supplierId,
      supplierName = supplierNameFinal ?: existingMat?.supplierName,
      purchasesTracked = true,
      syncPending = true,
      updatedAt = now,
    )
    dao.upsertRawMaterial(matEntity)
    enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, matEntity.id, SarimWebSchema.materialToMap(matEntity))

    val purchaseLot = (existingLot ?: RawMaterialPurchaseEntity(
      materialId = matEntity.id,
      materialName = matEntity.name,
      dateEpoch = dateEpoch,
      unitName = matEntity.unitName,
      unitsBought = unitsBought,
      weightPerUnitKg = weightPerUnitKg,
      costPerUnit = costPerUnit,
      totalKg = totalKg,
      totalAmount = totalAmount,
      supplierMode = supplierMode,
      supplierId = supplierId,
      supplierName = supplierNameFinal,
      paymentStatus = if (applyPayableToLot > 0.001) "pending" else "paid",
      totalPayable = applyPayableToLot,
      purchaseDate = SarimFormat.dateInput(dateEpoch),
      updatedAt = now,
    )).copy(
      materialId = matEntity.id,
      materialName = matEntity.name,
      dateEpoch = dateEpoch,
      unitName = matEntity.unitName,
      unitsBought = unitsBought,
      weightPerUnitKg = weightPerUnitKg,
      costPerUnit = costPerUnit,
      totalKg = totalKg,
      totalAmount = totalAmount,
      supplierMode = supplierMode,
      supplierId = supplierId,
      supplierName = supplierNameFinal,
      paymentStatus = if (applyPayableToLot > 0.001) "pending" else "paid",
      totalPayable = applyPayableToLot,
      purchaseDate = SarimFormat.dateInput(dateEpoch),
      syncPending = true,
      updatedAt = now,
    )
    dao.upsertPurchase(purchaseLot)
    enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, purchaseLot.id, SarimWebSchema.purchaseLotToMap(purchaseLot))
  }

  suspend fun deletePurchaseLot(lotId: String) {
    val now = System.currentTimeMillis()
    val lot = dao.getAllPurchases().firstOrNull { it.id == lotId } ?: return
    val mat = dao.getAllRawMaterials().firstOrNull { it.id == lot.materialId && !it.isDeleted }
    SarimLinkEngine.getPurchaseLotDeleteBlockReason(lot, mat)?.let { msg ->
      throw IllegalStateException(msg)
    }
    if (mat != null) {
      val newStock = (mat.stockKg - lot.totalKg).coerceAtLeast(0.0)
      val newCollected = (mat.collectedKg - lot.totalKg).coerceAtLeast(0.0)
      val newAvg = if (newStock > 0.0001) {
        ((mat.stockKg * mat.avgCostPerKg - lot.totalAmount).coerceAtLeast(0.0)) / newStock
      } else 0.0
      val newPending = (mat.pendingSupplierAmount - lot.totalPayable).coerceAtLeast(0.0)
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
      enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updatedMat.id, SarimWebSchema.materialToMap(updatedMat))
    }
    if (!lot.supplierId.isNullOrBlank() && lot.totalPayable > 0.0) {
      dao.getAllPaymentEntities().firstOrNull { it.id == lot.supplierId && !it.isDeleted }?.let { sup ->
        val updatedSup = sup.copy(
          currentBalance = sup.currentBalance - lot.totalPayable,
          syncPending = true,
          updatedAt = now,
        )
        dao.upsertPaymentEntity(updatedSup)
        enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, updatedSup.id, SarimWebSchema.entityToMap(updatedSup))
      }
    }
    dao.softDeletePurchase(lotId, now)
    enqueueOutboxDelete(
      collection = SarimWebSchema.COL_INVENTORY,
      docId = lotId,
      title = "Purchase: ${lot.materialName}",
      subtitle = "${lot.unitsBought} ${lot.unitName} · ${lot.totalKg} kg",
      amountText = "Rs ${lot.totalAmount}",
      snapshotMap = SarimWebSchema.purchaseLotToMap(lot),
      recordType = "purchase_lot",
    )
  }

  suspend fun deleteRawMaterial(id: String) {
    val now = System.currentTimeMillis()
    val mat = dao.getAllRawMaterials().firstOrNull { it.id == id }
    val lots = dao.getAllPurchases().filter { !it.isDeleted && it.materialId == id }
    val groupId = if (lots.isNotEmpty()) SarimUuid.generateUUID("${SarimUuid.PREFIX_GRP}_mat", tsMs = now) else null

    lots.forEach { lot ->
      if (!lot.supplierId.isNullOrBlank() && lot.totalPayable > 0.0) {
        dao.getAllPaymentEntities().firstOrNull { it.id == lot.supplierId && !it.isDeleted }?.let { sup ->
          val updatedSup = sup.copy(
            currentBalance = sup.currentBalance - lot.totalPayable,
            syncPending = true,
            updatedAt = now,
          )
          dao.upsertPaymentEntity(updatedSup)
          enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, updatedSup.id, SarimWebSchema.entityToMap(updatedSup))
        }
      }
      dao.softDeletePurchase(lot.id, now)
      enqueueOutboxDelete(
        collection = SarimWebSchema.COL_INVENTORY,
        docId = lot.id,
        title = "Purchase: ${lot.materialName}",
        subtitle = "${lot.totalKg} kg",
        amountText = "Rs ${lot.totalAmount}",
        snapshotMap = SarimWebSchema.purchaseLotToMap(lot),
        groupId = groupId,
        recordType = "purchase_lot",
      )
    }

    dao.softDeleteRawMaterial(id, now)
    enqueueOutboxDelete(
      collection = SarimWebSchema.COL_INVENTORY,
      docId = id,
      title = "Material: ${mat?.name ?: id}",
      subtitle = "${mat?.stockKg ?: 0.0} kg",
      amountText = "Rs ${mat?.avgCostPerKg ?: 0.0}/kg",
      snapshotMap = mat?.let { SarimWebSchema.materialToMap(it) },
      groupId = groupId,
    )
  }

  /**
   * Mirrors `unlinkSupplierFromMaterial` in `modules/factory-core.js`:
   * explicitly unlinks a supplier from a raw material, clears supplier metadata on the material and
   * matching purchase lots, and reverses linked payable balances/transactions.
   */
  suspend fun unlinkSupplierFromMaterial(
    materialId: String,
    reverseTransactions: Boolean = true,
  ): Int {
    val now = System.currentTimeMillis()
    val mat = dao.getAllRawMaterials().firstOrNull { !it.isDeleted && it.id == materialId }
      ?: throw IllegalStateException("Material not found.")
    val oldSupplierId = mat.supplierId?.takeIf { it.isNotBlank() } ?: return 0
    val groupId = SarimUuid.generateUUID("${SarimUuid.PREFIX_GRP}_unlink", tsMs = now)
    var reversedCount = 0

    // 1. Reverse & soft-delete explicit payable PaymentTransaction records linked to this material + supplier
    val linkedPayables = dao.getAllPaymentTransactions().filter { tx ->
      !tx.isDeleted && tx.isPayable && tx.entityId == oldSupplierId &&
        (tx.materialId == materialId || materialId in JsonCodecs.decodeStrings(tx.materialIdsJson))
    }
    var txRevertedAmount = 0.0
    if (reverseTransactions) {
      linkedPayables.forEach { tx ->
        txRevertedAmount += if (tx.type == "PAYMENT_IN") tx.amount else -tx.amount
        revertPaymentTransactionEffects(tx, now)
        tx.ledgerId?.takeIf { it.isNotBlank() }?.let { lid ->
          dao.softDeleteLedgerEntry(lid, now)
          touchTimestampKey("daily_ledger_timestamp", SarimWebSchema.COL_DAILY_LEDGER, now)
        }
        dao.softDeletePaymentTransaction(tx.id, now)
        enqueueOutboxDelete(
          collection = SarimWebSchema.COL_TRANSACTIONS,
          docId = tx.id,
          title = "${tx.type}: ${tx.entityOrExpenseName}",
          subtitle = tx.description,
          amountText = "Rs ${tx.amount}",
          snapshotMap = SarimWebSchema.transactionToMap(tx),
          groupId = groupId,
        )
        reversedCount++
      }
    }

    // 2. Unlink matching purchase lots and compute any direct supplier payable not already covered by linkedPayables
    val linkedLots = dao.getAllPurchases().filter { !it.isDeleted && it.materialId == materialId && it.supplierId == oldSupplierId }
    var lotPayableSum = 0.0
    linkedLots.forEach { lot ->
      lotPayableSum += lot.totalPayable.coerceAtLeast(0.0)
      val clearedLot = lot.copy(
        supplierMode = "NONE",
        supplierId = null,
        supplierName = null,
        totalPayable = 0.0,
        paymentStatus = "pending",
        syncPending = true,
        updatedAt = now,
      )
      dao.upsertPurchase(clearedLot)
      enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, clearedLot.id, SarimWebSchema.purchaseLotToMap(clearedLot))
      reversedCount++
    }

    // 3. Reconcile supplier entity balance if lots carried direct payable beyond any linked IN payables
    val remainingLotBalanceToReverse = (lotPayableSum - txRevertedAmount.coerceAtLeast(0.0)).coerceAtLeast(0.0)
    if (reverseTransactions && remainingLotBalanceToReverse > 0.001) {
      dao.getAllPaymentEntities().firstOrNull { !it.isDeleted && it.id == oldSupplierId }?.let { sup ->
        val updatedSup = sup.copy(
          currentBalance = sup.currentBalance - remainingLotBalanceToReverse,
          syncPending = true,
          updatedAt = now,
        )
        dao.upsertPaymentEntity(updatedSup)
        enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, updatedSup.id, SarimWebSchema.entityToMap(updatedSup))
      }
    }

    // 4. Clear supplier link on the raw material and reset payable status
    val updatedMat = mat.copy(
      supplierId = null,
      supplierName = null,
      pendingSupplierAmount = 0.0,
      paymentStatus = "pending",
      syncPending = true,
      updatedAt = now,
    )
    dao.upsertRawMaterial(updatedMat)
    enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updatedMat.id, SarimWebSchema.materialToMap(updatedMat))
    return reversedCount + 1
  }

  suspend fun saveFormula(
    editingId: String?,
    name: String,
    additionalCost: Double,
    costFactor: Double,
    ingredients: List<FormulaIngredient>,
  ) {
    val now = System.currentTimeMillis()
    val liveFormulas = dao.getAllFormulas().filter { !it.isDeleted }.associateBy { it.id }
    val existing = editingId?.let { id -> dao.getAllFormulas().firstOrNull { it.id == id } }
    val engineFormulas = FormulaCalculator.engineFormulas(liveFormulas)
    ingredients.filter { it.refType.equals("FORMULA", ignoreCase = true) }.forEach { link ->
      if (liveFormulas[link.refId] == null) {
        throw IllegalStateException("Linked formula ${link.refName.ifBlank { "(unknown)" }} no longer exists.")
      }
      if (existing != null && !SarimFormulaEngine.canLink(engineFormulas, existing.id, link.refId)) {
        throw IllegalStateException("Cannot link ${link.refName}: it already uses this formula, which would create a loop.")
      }
    }
    dao.upsertFormula(
      (existing ?: FormulaEntity(name = name.trim())).copy(
        name = name.trim().uppercase(),
        additionalCostPerUnit = additionalCost,
        costAdjustmentFactor = costFactor,
        ingredientsJson = JsonCodecs.encodeIngredients(ingredients),
        syncPending = true,
        updatedAt = now,
      )
    )
    touchTimestampKey("formulas_timestamp", SarimWebSchema.COL_FORMULAS, now)
  }

  suspend fun deleteFormula(id: String) {
    val now = System.currentTimeMillis()
    val f = dao.getAllFormulas().firstOrNull { it.id == id }
    if (f != null) {
      SarimLinkEngine.getFormulaDeleteBlockReason(
        formula = f,
        stores = dao.getAllStores(),
        formulas = dao.getAllFormulas(),
      )?.let { msg -> throw IllegalStateException(msg) }
    }
    dao.softDeleteFormula(id, now)
    val snapshot = f?.let {
      linkedMapOf<String, Any?>(
        "id" to it.id,
        "name" to it.name,
        "additionalCost" to it.additionalCostPerUnit,
        "factor" to it.costAdjustmentFactor,
        "unitsAvailable" to it.unitsAvailable,
        "unitsUsed" to it.unitsUsed,
      )
    }
    recordSingletonDeletion(
      collection = SarimWebSchema.COL_FORMULAS,
      docId = id,
      title = f?.name ?: "Formula",
      subtitle = "${f?.unitsAvailable ?: 0.0} units",
      amountText = "Rs ${f?.additionalCostPerUnit ?: 0.0}",
      snapshotMap = snapshot,
    )
  }

  private fun missingMaterialName(matId: String, root: FormulaEntity, formulas: Collection<FormulaEntity>): String {
    for (f in listOf(root) + formulas) {
      JsonCodecs.decodeIngredients(f.ingredientsJson)
        .firstOrNull { it.refId == matId && it.refName.isNotBlank() && !com.example.util.SarimDisplayNames.looksLikeId(it.refName) }
        ?.let { return it.refName.trim() }
    }
    return "an unnamed material"
  }

  suspend fun saveFactoryBatch(
    dateEpoch: Long,
    formula: FormulaEntity,
    units: Double,
    editingId: String? = null,
  ) {
    val now = System.currentTimeMillis()
    val existing = editingId?.let { id -> dao.getAllFactoryBatches().firstOrNull { it.id == id && !it.isDeleted } }

    // Validate before any DB mutation so failed checks leave stocks & formulas untouched
    val allFormulasInit = dao.getAllFormulas().filter { !it.isDeleted }.associateBy { it.id }.toMutableMap()
    val allMaterialsInit = dao.getAllRawMaterials().filter { !it.isDeleted }.associateBy { it.id }.toMutableMap()
    if (existing != null) {
      val oldFormula = allFormulasInit[existing.formulaId]
      if (oldFormula != null && (oldFormula.unitsAvailable + 0.0005) < existing.unitsProduced) {
        throw IllegalStateException("Some units from this batch have already been consumed.")
      }
      val oldMatsUsed = JsonCodecs.decodeFormulaMaterialsUsed(existing.materialsUsedJson)
      if (oldMatsUsed.isNotEmpty()) {
        oldMatsUsed.forEach { used ->
          allMaterialsInit[used.id]?.let { mat ->
            allMaterialsInit[mat.id] = mat.copy(stockKg = mat.stockKg + used.quantity)
          }
        }
      } else if (oldFormula != null) {
        val flatOld = FormulaCalculator.flattenRawMaterials(oldFormula, existing.unitsProduced, allFormulasInit, allMaterialsInit)
        flatOld.forEach { (matId, kgUsed) ->
          allMaterialsInit[matId]?.let { mat ->
            allMaterialsInit[mat.id] = mat.copy(stockKg = mat.stockKg + kgUsed)
          }
        }
      }
      if (oldFormula != null) {
        allFormulasInit[oldFormula.id] = oldFormula.copy(
          unitsAvailable = (oldFormula.unitsAvailable - existing.unitsProduced).coerceAtLeast(0.0),
        )
      }
    }

    val targetFormulaCheck = allFormulasInit[formula.id] ?: formula
    val flatRawCheck = FormulaCalculator.flattenRawMaterials(targetFormulaCheck, units, allFormulasInit, allMaterialsInit)
    flatRawCheck.forEach { (matId, kgUsed) ->
      val mat = allMaterialsInit[matId]
        ?: throw IllegalStateException(
          "Raw material \"${missingMaterialName(matId, targetFormulaCheck, allFormulasInit.values)}\" is missing or was deleted, " +
            "but ${targetFormulaCheck.name} needs it. Open Formula Store and re-select that ingredient, or add the material again."
        )
      if (mat.stockKg + 1e-6 < kgUsed) {
        throw IllegalStateException(
          "Insufficient ${mat.name} stock: need ${SarimLinkEngine.round2(kgUsed)} kg, available ${SarimLinkEngine.round2(mat.stockKg)} kg."
        )
      }
    }

    // All checks passed — apply reversals (if edit) & new deductions
    if (existing != null) {
      val priorFormulas = dao.getAllFormulas().filter { !it.isDeleted }.associateBy { it.id }
      val oldFormula = priorFormulas[existing.formulaId]
      val priorMats = dao.getAllRawMaterials().filter { !it.isDeleted }.associateBy { it.id }
      val oldMatsUsed = JsonCodecs.decodeFormulaMaterialsUsed(existing.materialsUsedJson)
      if (oldMatsUsed.isNotEmpty()) {
        oldMatsUsed.forEach { used ->
          priorMats[used.id]?.let { mat ->
            val restored = mat.copy(stockKg = mat.stockKg + used.quantity, syncPending = true, updatedAt = now)
            dao.upsertRawMaterial(restored)
            enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, restored.id, SarimWebSchema.materialToMap(restored))
          }
        }
      } else if (oldFormula != null) {
        val flatOld = FormulaCalculator.flattenRawMaterials(oldFormula, existing.unitsProduced, priorFormulas, priorMats)
        flatOld.forEach { (matId, kgUsed) ->
          priorMats[matId]?.let { mat ->
            val restored = mat.copy(stockKg = mat.stockKg + kgUsed, syncPending = true, updatedAt = now)
            dao.upsertRawMaterial(restored)
            enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, restored.id, SarimWebSchema.materialToMap(restored))
          }
        }
      }
      if (oldFormula != null) {
        dao.upsertFormula(
          oldFormula.copy(
            unitsAvailable = (oldFormula.unitsAvailable - existing.unitsProduced).coerceAtLeast(0.0),
            syncPending = true,
            updatedAt = now,
          )
        )
      }
    }

    val allFormulas = dao.getAllFormulas().filter { !it.isDeleted }.associateBy { it.id }
    val allMaterials = dao.getAllRawMaterials().filter { !it.isDeleted }.associateBy { it.id }
    val targetFormula = allFormulas[formula.id] ?: formula
    val summary = FormulaCalculator.computeFormulaSummary(targetFormula, allFormulas, allMaterials)
    val flatRaw = FormulaCalculator.flattenRawMaterials(targetFormula, units, allFormulas, allMaterials)
    var rawUsedKg = 0.0
    val matsUsedList = mutableListOf<JsonCodecs.FormulaMaterialUsed>()
    flatRaw.forEach { (matId, kgUsed) ->
      rawUsedKg += kgUsed
      val mat = allMaterials[matId]
      matsUsedList += JsonCodecs.FormulaMaterialUsed(
        id = matId,
        name = mat?.name ?: matId,
        quantity = kgUsed,
        cost = kgUsed * (mat?.avgCostPerKg ?: 0.0),
      )
      if (mat != null) {
        val updated = mat.copy(
          stockKg = (mat.stockKg - kgUsed).coerceAtLeast(0.0),
          syncPending = true,
          updatedAt = now,
        )
        dao.upsertRawMaterial(updated)
        enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updated.id, SarimWebSchema.materialToMap(updated))
      }
    }

    val additionalTotal = targetFormula.additionalCostPerUnit * units
    val materialsTotal = summary.rawMaterialCostPerUnit * units
    val totalCost = materialsTotal + additionalTotal
    val outputKg = summary.unitWeightKg * units

    dao.upsertFormula(
      targetFormula.copy(
        unitsAvailable = targetFormula.unitsAvailable + units,
        syncPending = true,
        updatedAt = now,
      )
    )
    touchTimestampKey("formulas_timestamp", SarimWebSchema.COL_FORMULAS, now)

    val batch = FactoryBatchEntity(
      id = existing?.id ?: SarimUuid.generateUUID(SarimUuid.PREFIX_FPROD, tsMs = now),
      dateEpoch = dateEpoch,
      formulaId = targetFormula.id,
      formulaName = targetFormula.name,
      unitsProduced = units,
      rawUsedKg = rawUsedKg,
      outputKg = outputKg,
      materialsCost = materialsTotal,
      additionalCost = additionalTotal,
      totalCost = totalCost,
      costPerUnit = summary.totalCostPerUnit,
      dateStr = SarimFormat.dateInput(dateEpoch),
      timeStr = existing?.timeStr?.takeIf { it.isNotBlank() } ?: SarimFormat.formatTime(now),
      materialsUsedJson = JsonCodecs.encodeFormulaMaterialsUsed(matsUsedList),
      createdAt = existing?.createdAt ?: now,
      updatedAt = now,
    )
    dao.upsertFactoryBatch(batch)
    enqueueOutboxSet(SarimWebSchema.COL_FACTORY, batch.id, SarimWebSchema.factoryBatchToMap(batch))
  }

  suspend fun deleteFactoryBatch(id: String) {
    val now = System.currentTimeMillis()
    val batch = dao.getAllFactoryBatches().firstOrNull { it.id == id } ?: return
    val allMats = dao.getAllRawMaterials().filter { !it.isDeleted }.associateBy { it.id }
    val allFormulas = dao.getAllFormulas().filter { !it.isDeleted }.associateBy { it.id }

    SarimLinkEngine.getFactoryBatchDeleteBlockReason(
      batch = batch,
      formula = allFormulas[batch.formulaId],
      factoryBatches = dao.getAllFactoryBatches(),
      production = dao.getAllProductionEntries(),
    )?.let { msg -> throw IllegalStateException(msg) }

    val matsUsed = JsonCodecs.decodeFormulaMaterialsUsed(batch.materialsUsedJson)
    if (matsUsed.isNotEmpty()) {
      matsUsed.forEach { used ->
        allMats[used.id]?.let { mat ->
          val updated = mat.copy(stockKg = mat.stockKg + used.quantity, syncPending = true, updatedAt = now)
          dao.upsertRawMaterial(updated)
          enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updated.id, SarimWebSchema.materialToMap(updated))
        }
      }
    } else {
      allFormulas[batch.formulaId]?.let { formula ->
        val flatRaw = FormulaCalculator.flattenRawMaterials(formula, batch.unitsProduced, allFormulas, allMats)
        flatRaw.forEach { (matId, kgUsed) ->
          allMats[matId]?.let { mat ->
            val updated = mat.copy(stockKg = mat.stockKg + kgUsed, syncPending = true, updatedAt = now)
            dao.upsertRawMaterial(updated)
            enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updated.id, SarimWebSchema.materialToMap(updated))
          }
        }
      }
    }

    allFormulas[batch.formulaId]?.let { formula ->
      dao.upsertFormula(
        formula.copy(
          unitsAvailable = (formula.unitsAvailable - batch.unitsProduced).coerceAtLeast(0.0),
          syncPending = true,
          updatedAt = now,
        )
      )
      touchTimestampKey("formulas_timestamp", SarimWebSchema.COL_FORMULAS, now)
    }

    dao.softDeleteFactoryBatch(id, now)
    enqueueOutboxDelete(
      collection = SarimWebSchema.COL_FACTORY,
      docId = id,
      title = "Factory Batch: ${batch.formulaName}",
      subtitle = "${batch.unitsProduced} units · ${batch.outputKg} kg",
      amountText = "Rs ${batch.totalCost}",
      snapshotMap = SarimWebSchema.factoryBatchToMap(batch),
    )
  }

  private suspend fun syncCustomerOldDebtTransaction(cust: CustomerEntity, oldName: String?, now: Long) {
    val col = if (cust.isRepCustomer) SarimWebSchema.COL_REP else SarimWebSchema.COL_SALES
    val existingOld = dao.getAllSales().firstOrNull {
      !it.isDeleted && (it.mode == "OLD_DEBT" || it.transactionType == "OLD_DEBT") &&
        (it.customerId == cust.id || (it.isRepSale == cust.isRepCustomer && (it.customerName.equals(cust.name, true) || (oldName != null && it.customerName.equals(oldName, true)))))
    }
    if (cust.oldDebit > 0.001) {
      val partial = existingOld?.partialPaymentReceived ?: 0.0
      val tx = SaleTransactionEntity(
        id = existingOld?.id ?: SarimUuid.generateUUID(SarimUuid.PREFIX_OLD_DEBT, tsMs = now),
        dateEpoch = existingOld?.dateEpoch ?: cust.createdAt,
        mode = "OLD_DEBT",
        customerId = cust.id,
        customerName = cust.name,
        repName = if (cust.isRepCustomer) cust.repName.ifBlank { "Direct" } else "ADMIN",
        storeKey = "",
        storeName = "",
        quantityKg = 0.0,
        pricePerKg = 0.0,
        totalValue = cust.oldDebit,
        totalCost = 0.0,
        profit = 0.0,
        paymentType = "CREDIT",
        amountReceived = 0.0,
        balanceAfter = cust.currentCredit,
        addToLedger = false,
        isRepSale = cust.isRepCustomer,
        dateStr = existingOld?.dateStr ?: SarimFormat.dateInput(cust.createdAt),
        timeStr = existingOld?.timeStr ?: SarimFormat.formatTime(cust.createdAt),
        customerPhone = cust.phone,
        creditReceived = partial >= cust.oldDebit - 0.01,
        isCollection = false,
        notes = "Previous balance",
        transactionType = "OLD_DEBT",
        affectsInventory = false,
        partialPaymentReceived = partial,
        collectionAllocsJson = existingOld?.collectionAllocsJson ?: "[]",
        syncPending = true,
        createdAt = existingOld?.createdAt ?: cust.createdAt,
        updatedAt = now,
      )
      dao.upsertSale(tx)
      enqueueOutboxSet(col, tx.id, SarimWebSchema.saleToMap(tx))
    } else if (existingOld != null) {
      dao.softDeleteSale(existingOld.id, now)
      enqueueOutboxDelete(
        collection = col,
        docId = existingOld.id,
        title = "Old Debt: ${cust.name}",
        subtitle = "Previous balance cleared",
        amountText = "Rs ${existingOld.totalValue}",
        snapshotMap = SarimWebSchema.saleToMap(existingOld),
      )
    }
  }

  suspend fun saveCustomer(
    editingId: String?,
    name: String,
    phone: String,
    address: String,
    oldDebit: Double,
    customSalePrice: Double?,
    ledgerPage: String,
    photoUri: String,
    isRepCustomer: Boolean,
    repName: String,
    actor: String = "Admin",
    locationConfirmed: Boolean? = null,
  ): CustomerEntity {
    val now = System.currentTimeMillis()
    val existing = editingId?.let { id -> dao.getAllCustomers().firstOrNull { it.id == id } }
    val cleanName = name.trim()
    val cleanPhone = phone.trim()
    val cleanAddress = address.trim()
    val cleanPage = ledgerPage.trim().take(12)
    val cleanRep = repName.trim()
    val history = existing?.let { JsonCodecs.decodeEditHistory(it.editHistoryJson) }?.toMutableList() ?: mutableListOf()
    if (existing != null) {
      val changes = SarimLinkEngine.buildEditDiffs(
        listOf(
          QuadrupleDiff("name", "Name", existing.name, cleanName),
          QuadrupleDiff("phone", "Phone", existing.phone, cleanPhone),
          QuadrupleDiff("address", "Address", existing.address, cleanAddress),
          QuadrupleDiff("oldDebit", "Old Debit", existing.oldDebit.toString(), oldDebit.toString()),
          QuadrupleDiff("customSalePrice", "Custom Rate", existing.customSalePrice?.toString() ?: "Default", customSalePrice?.toString() ?: "Default"),
          QuadrupleDiff("ledgerPage", "Ledger Page", existing.ledgerPage, cleanPage),
          QuadrupleDiff("repName", "Rep", existing.repName, cleanRep),
        )
      )
      val summaryText = if (changes.isNotEmpty()) {
        "Updated details for $cleanName"
      } else {
        "Saved profile for $cleanName"
      }
      history.add(0, EditHistoryEntry(now, summaryText, by = actor, changes = changes))
    }
    val deltaOldDebit = oldDebit - (existing?.oldDebit ?: 0.0)
    val resolvedConfirmed = locationConfirmed ?: when {
      existing == null -> false
      cleanAddress != existing.address.trim() && !cleanAddress.startsWith("GPS:", ignoreCase = true) -> false
      else -> existing.locationConfirmed
    }
    val entity = (existing ?: CustomerEntity(
      id = SarimUuid.generateUUID(if (isRepCustomer) SarimUuid.PREFIX_REP_CUST else SarimUuid.PREFIX_CUST, tsMs = now),
      name = cleanName,
    )).copy(
      name = cleanName,
      phone = cleanPhone,
      address = cleanAddress,
      oldDebit = oldDebit,
      customSalePrice = customSalePrice,
      ledgerPage = cleanPage,
      photoUri = photoUri,
      isRepCustomer = isRepCustomer,
      repName = cleanRep,
      locationConfirmed = resolvedConfirmed,
      currentCredit = (existing?.currentCredit ?: 0.0) + deltaOldDebit,
      editHistoryJson = JsonCodecs.encodeEditHistory(history.take(100)),
      syncPending = true,
      updatedAt = now,
    )
    dao.upsertCustomer(entity)
    val targetCol = if (isRepCustomer) SarimWebSchema.COL_CLIENTS else SarimWebSchema.COL_CUSTOMERS
    enqueueOutboxSet(targetCol, entity.id, SarimWebSchema.customerToMap(entity))

    if (existing != null && (!existing.name.equals(cleanName, true) || existing.phone != entity.phone)) {
      val saleCol = if (entity.isRepCustomer) SarimWebSchema.COL_REP else SarimWebSchema.COL_SALES
      var touchedLedger = false
      dao.getAllSales().filter {
        !it.isDeleted && it.isRepSale == entity.isRepCustomer &&
          (it.customerId == entity.id || it.customerName.equals(existing.name, true))
      }.forEach { s ->
        val updatedTx = s.copy(
          customerId = entity.id,
          customerName = cleanName,
          customerPhone = entity.phone,
          syncPending = true,
          updatedAt = now,
        )
        dao.upsertSale(updatedTx)
        enqueueOutboxSet(saleCol, updatedTx.id, SarimWebSchema.saleToMap(updatedTx))
        s.ledgerId?.takeIf { it.isNotBlank() }?.let { lid ->
          dao.getAllDailyLedger().firstOrNull { it.id == lid && !it.isDeleted }?.let { led ->
            dao.upsertLedgerEntry(led.copy(party = cleanName, updatedAt = now))
            touchedLedger = true
          }
        }
      }
      if (!entity.isRepCustomer) {
        dao.getAllDailyLedger().filter {
          !it.isDeleted && it.party.equals(existing.name, true)
        }.forEach { led ->
          val link = JsonCodecs.decodeLedgerLink(led.linkJson)
          if (link?.get("kind") == "customer") {
            val updatedLink = link.toMutableMap().apply { this["name"] = cleanName }
            dao.upsertLedgerEntry(
              led.copy(party = cleanName, linkJson = JsonCodecs.encodeLedgerLink(updatedLink), updatedAt = now)
            )
            touchedLedger = true
          }
        }
      }
      if (touchedLedger) touchTimestampKey("daily_ledger_timestamp", SarimWebSchema.COL_DAILY_LEDGER, now)
      val oldPKey = customerPhotoKey(existing)
      val newPKey = customerPhotoKey(entity)
      if (oldPKey != newPKey) {
        markPhotoDeleted(oldPKey, now)
      }
    }

    syncCustomerOldDebtTransaction(entity, existing?.name, now)

    val pKey = customerPhotoKey(entity)
    if (photoUri.isNotBlank()) {
      upsertPhotoKey(pKey, photoUri, now)
    } else if (existing?.photoUri?.isNotBlank() == true) {
      markPhotoDeleted(pKey, now)
    }
    return entity
  }

  suspend fun forgetLedgerParty(partyName: String, now: Long = System.currentTimeMillis()): Int {
    val clean = partyName.trim()
    if (clean.isBlank()) return 0
    var clearedCount = 0
    dao.getAllDailyLedger().filter {
      !it.isDeleted && it.sourceType == "MANUAL" && it.type != "TRANSFER" && it.party.equals(clean, ignoreCase = true)
    }.forEach { led ->
      dao.upsertLedgerEntry(led.copy(party = "", updatedAt = now))
      clearedCount++
    }
    if (clearedCount > 0) touchTimestampKey("daily_ledger_timestamp", SarimWebSchema.COL_DAILY_LEDGER, now)
    return clearedCount
  }

  suspend fun deleteCustomer(id: String) {
    val now = System.currentTimeMillis()
    val c = dao.getAllCustomers().firstOrNull { it.id == id }
    val targetCol = if (c?.isRepCustomer == true) SarimWebSchema.COL_CLIENTS else SarimWebSchema.COL_CUSTOMERS
    val groupId = SarimUuid.generateUUID("${SarimUuid.PREFIX_GRP}_cust", tsMs = now)

    if (c != null) {
      val saleCol = if (c.isRepCustomer) SarimWebSchema.COL_REP else SarimWebSchema.COL_SALES
      dao.getAllSales().filter {
        !it.isDeleted && it.isRepSale == c.isRepCustomer &&
          (it.customerId == c.id || it.customerName.equals(c.name, true))
      }.forEach { s ->
        s.ledgerId?.takeIf { it.isNotBlank() }?.let { lid ->
          dao.softDeleteLedgerEntry(lid, now)
          touchTimestampKey("daily_ledger_timestamp", SarimWebSchema.COL_DAILY_LEDGER, now)
        }
        dao.softDeleteSale(s.id, now)
        enqueueOutboxDelete(
          collection = saleCol,
          docId = s.id,
          title = "${s.mode}: ${s.customerName}",
          subtitle = "${s.quantityKg} kg",
          amountText = "Rs ${s.totalValue}",
          snapshotMap = SarimWebSchema.saleToMap(s),
          groupId = groupId,
        )
      }
      forgetLedgerParty(c.name, now)
      markPhotoDeleted(customerPhotoKey(c), now)
    }

    dao.softDeleteCustomer(id, now)
    enqueueOutboxDelete(
      collection = targetCol,
      docId = id,
      title = c?.name ?: "Customer",
      subtitle = c?.phone ?: "",
      amountText = "",
      snapshotMap = c?.let { SarimWebSchema.customerToMap(it) },
      groupId = groupId,
    )
  }

  private suspend fun applySaleReversalOnDeleteOrEdit(sale: SaleTransactionEntity, now: Long) {
    val saleCol = if (sale.isRepSale) SarimWebSchema.COL_REP else SarimWebSchema.COL_SALES

    // Reverse FIFO child -> parent allocations
    val allocs = JsonCodecs.decodeAllocations(sale.allocationsJson)
    if (allocs.isNotEmpty()) {
      val allSales = dao.getAllSales()
      allocs.forEach { alloc ->
        allSales.firstOrNull { it.id == alloc.saleId && !it.isDeleted }?.let { parent ->
          val newPartial = (parent.partialPaymentReceived - alloc.amount).coerceAtLeast(0.0)
          val newCollAllocs = JsonCodecs.decodeCollectionAllocs(parent.collectionAllocsJson).filter { it.cid != sale.id }
          val updatedParent = parent.copy(
            partialPaymentReceived = newPartial,
            collectionAllocsJson = JsonCodecs.encodeCollectionAllocs(newCollAllocs),
            creditReceived = newPartial >= parent.totalValue - 0.01,
            syncPending = true,
            updatedAt = now,
          )
          dao.upsertSale(updatedParent)
          enqueueOutboxSet(saleCol, updatedParent.id, SarimWebSchema.saleToMap(updatedParent))
        }
      }
    }

    // Reverse Customer totals (only when record actually belongs to a registered customer rather than a Rep-linked Admin delivery)
    val isRepLinkedAdmin = SarimLinkEngine.isRepLinkedAdminSale(sale) || sale.isRepReturn
    if (!isRepLinkedAdmin) {
      val cashCollected = if (sale.collectedAmount > 0.0) sale.collectedAmount else sale.amountReceived
      dao.getAllCustomers().firstOrNull {
        !it.isDeleted && it.isRepCustomer == sale.isRepSale &&
          (it.id == sale.customerId || it.name.equals(sale.customerName, true))
      }?.let { cust ->
        val creditDelta = when (sale.mode) {
          "SALE" -> if (sale.paymentType == "CREDIT") -sale.totalValue else 0.0
          "COLLECTION" -> cashCollected + sale.expiredValue
          "MONEY_OUT" -> -sale.totalValue.takeIf { it > 0.0 }.let { it ?: cashCollected }
          "OLD_DEBT" -> -sale.totalValue
          else -> 0.0
        }
        val qtyDelta = if (sale.mode == "SALE") -sale.quantityKg else 0.0
        val valDelta = if (sale.mode == "SALE") -sale.totalValue else 0.0
        val recDelta = when {
          sale.mode == "COLLECTION" -> -cashCollected
          sale.mode == "SALE" && sale.paymentType == "CASH" -> -sale.totalValue
          else -> 0.0
        }
        val updatedCust = cust.copy(
          currentCredit = cust.currentCredit + creditDelta,
          totalQuantityKg = (cust.totalQuantityKg + qtyDelta).coerceAtLeast(0.0),
          totalSaleValue = (cust.totalSaleValue + valDelta).coerceAtLeast(0.0),
          totalReceived = (cust.totalReceived + recDelta).coerceAtLeast(0.0),
          syncPending = true,
          updatedAt = now,
        )
        dao.upsertCustomer(updatedCust)
        val custCol = if (updatedCust.isRepCustomer) SarimWebSchema.COL_CLIENTS else SarimWebSchema.COL_CUSTOMERS
        enqueueOutboxSet(custCol, updatedCust.id, SarimWebSchema.customerToMap(updatedCust))
      }
    }

    // Reverse CHORA only for non-RepReturn collections where CHORA was incremented directly
    if (sale.expiredQtyKg > 0.0 && !sale.isRepReturn && sale.mode != "REP_RETURN") {
      dao.getAllRawMaterials().firstOrNull { it.name.equals("CHORA", ignoreCase = true) && !it.isDeleted }?.let { chora ->
        val updatedChora = chora.copy(
          stockKg = (chora.stockKg - sale.expiredQtyKg).coerceAtLeast(0.0),
          collectedKg = (chora.collectedKg - sale.expiredQtyKg).coerceAtLeast(0.0),
          syncPending = true,
          updatedAt = now,
        )
        dao.upsertRawMaterial(updatedChora)
        enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updatedChora.id, SarimWebSchema.materialToMap(updatedChora))
      }
    }

    // Remove linked ledger entry if present
    sale.ledgerId?.takeIf { it.isNotBlank() }?.let { lid ->
      dao.softDeleteLedgerEntry(lid, now)
      touchTimestampKey("daily_ledger_timestamp", SarimWebSchema.COL_DAILY_LEDGER, now)
    }
  }

  suspend fun saveSaleOrCollection(
    editingId: String?,
    dateEpoch: Long,
    mode: String, // SALE, COLLECTION, MONEY_OUT
    customerName: String,
    newCustomerPhone: String,
    repName: String,
    store: StoreEntity?,
    quantityKg: Double,
    costPerKg: Double,
    paymentType: String,
    amountReceived: Double,
    expiredQtyKg: Double,
    returnedQtyKg: Double,
    addToLedger: Boolean,
    isRepSale: Boolean,
    lat: Double? = null,
    lng: Double? = null,
    notes: String = "",
    costSource: String? = null,
    linkedLedgerId: String? = null,
    actor: String = "Admin",
  ): SaleTransactionEntity {
    val now = System.currentTimeMillis()
    val existing = editingId?.let { id -> dao.getAllSales().firstOrNull { it.id == id } }
    val allSettlementsPre = dao.getAllSettlements()

    val cleanRep = repName.trim().ifBlank { "Direct" }
    val isRepLinkedAdmin = !isRepSale && mode == "SALE" &&
      !cleanRep.equals("Direct", true) && !cleanRep.equals("NONE", true) && !cleanRep.equals("ADMIN", true)
    val effectiveCustomerName = if (isRepLinkedAdmin) cleanRep else customerName.trim()
    val effectivePaymentType = if (isRepLinkedAdmin) "CREDIT" else paymentType
    val effectiveAddToLedger = if (isRepLinkedAdmin) false else addToLedger

    val allCusts = dao.getAllCustomers().filter { !it.isDeleted }
    val existingCustMatch = if (isRepLinkedAdmin) null else {
      allCusts.firstOrNull { it.name.equals(effectiveCustomerName, ignoreCase = true) && it.isRepCustomer == isRepSale }
    }
    val repPriceContact = if (isRepLinkedAdmin) {
      allCusts.firstOrNull { !it.isRepCustomer && it.name.equals(effectiveCustomerName, ignoreCase = true) }
    } else null
    val fallbackStorePrice = store?.salePricePerKg ?: 400.0
    val anticipatedPricePerKg = existingCustMatch?.customSalePrice ?: repPriceContact?.customSalePrice ?: fallbackStorePrice
    val anticipatedTotal = if (mode == "SALE") SarimLinkEngine.round2(quantityKg * anticipatedPricePerKg) else amountReceived

    if (existing != null) {
      SarimLinkEngine.getSaleEditLinkIssue(
        existing = existing,
        newMode = mode,
        newPaymentType = effectivePaymentType,
        newCustomerName = effectiveCustomerName,
        newTotalValue = anticipatedTotal,
        settlements = allSettlementsPre,
      )?.let { msg -> throw IllegalStateException(msg) }
      if ((existing.mode == "COLLECTION" || existing.isCollection) && existing.expiredQtyKg > 0.0 && expiredQtyKg < existing.expiredQtyKg) {
        val diff = existing.expiredQtyKg - expiredQtyKg
        val choraStock = dao.getAllRawMaterials().firstOrNull { !it.isDeleted && it.name.equals("CHORA", true) }?.stockKg ?: 0.0
        if (choraStock + 1e-6 < diff) {
          throw IllegalStateException("Cannot reduce expired qty by ${SarimLinkEngine.round2(diff)} kg: insufficient CHORA stock.")
        }
      }
      applySaleReversalOnDeleteOrEdit(existing, now)
    }

    var cust = if (isRepLinkedAdmin) {
      null
    } else {
      dao.getAllCustomers().firstOrNull {
        !it.isDeleted && it.name.equals(effectiveCustomerName, ignoreCase = true) && it.isRepCustomer == isRepSale
      } ?: saveCustomer(
        editingId = null,
        name = effectiveCustomerName,
        phone = newCustomerPhone,
        address = "",
        oldDebit = 0.0,
        customSalePrice = null,
        ledgerPage = "",
        photoUri = "",
        isRepCustomer = isRepSale,
        repName = if (isRepSale) cleanRep else "",
      )
    }

    val pricePerKg = cust?.customSalePrice ?: repPriceContact?.customSalePrice ?: fallbackStorePrice

    val allSalesNow = dao.getAllSales()
    val allSettlementsNow = dao.getAllSettlements()
    val txId = editingId ?: SarimUuid.generateUUID(SarimUuid.PREFIX_SALE, tsMs = now)
    val dateStr = SarimFormat.dateInput(dateEpoch)
    val saleCol = if (isRepSale) SarimWebSchema.COL_REP else SarimWebSchema.COL_SALES

    // Compute Goods Credit on Collection expired qty (matches web planGoodsCredit)
    var expUnitPrice = 0.0
    var expVal = 0.0
    if (mode == "COLLECTION" && expiredQtyKg > 0.0) {
      expUnitPrice = SarimLinkEngine.goodsCreditUnitPrice(
        name = effectiveCustomerName,
        sales = allSalesNow,
        fallbackPrice = pricePerKg,
        isRepProfile = isRepSale,
        excludeSaleId = txId,
      )
      val partyOwedAfterCash = (SarimLinkEngine.partyOutstanding(
        name = effectiveCustomerName,
        sales = allSalesNow,
        isRepProfile = isRepSale,
        settlements = allSettlementsNow,
        excludeSaleId = txId,
      ) - amountReceived).coerceAtLeast(0.0)
      expVal = SarimLinkEngine.planGoodsCredit(expiredQtyKg, expUnitPrice, partyOwedAfterCash).value
    }

    // Collection Allocation against unpaid CREDIT / OLD_DEBT sales (newest credit sales first, OLD_DEBT last)
    val allocList = mutableListOf<JsonCodecs.AllocationItem>()
    var cashLeftForCollection = amountReceived
    var expAllocated = 0.0
    if (mode == "COLLECTION" && (amountReceived > 0.0 || expVal > 0.0)) {
      val eligibleOpen = SarimLinkEngine.sortForCollection(
        SarimLinkEngine.collectionEligible(
          allSalesNow.filter {
            !it.isDeleted && it.id != txId && it.isRepSale == isRepSale &&
              it.customerName.equals(effectiveCustomerName, true) &&
              !it.creditReceived && it.usedInCalcId.isNullOrBlank() && !it.isRepTransfer &&
              (it.mode == "OLD_DEBT" || it.transactionType == "OLD_DEBT" || (it.mode == "SALE" && it.paymentType == "CREDIT"))
          },
          dateStr,
        )
      )
      val dues = eligibleOpen.map { it.id to (it.totalValue - it.partialPaymentReceived).coerceAtLeast(0.0) }
      val planned = SarimLinkEngine.planCollectionAllocation(amountReceived + expVal, dues)
      for (a in planned) {
        val parent = eligibleOpen.firstOrNull { it.id == a.saleId } ?: continue
        val newPartial = SarimLinkEngine.round2(parent.partialPaymentReceived + a.amount)
        val full = a.full || newPartial >= parent.totalValue - 0.01
        val existingChildAllocs = JsonCodecs.decodeCollectionAllocs(parent.collectionAllocsJson).toMutableList()
        existingChildAllocs.removeAll { it.cid == txId }
        existingChildAllocs += JsonCodecs.CollectionAllocItem(cid = txId, amount = a.amount, full = full)
        val updatedParent = parent.copy(
          partialPaymentReceived = newPartial,
          creditReceived = full,
          collectionAllocsJson = JsonCodecs.encodeCollectionAllocs(existingChildAllocs),
          syncPending = true,
          updatedAt = now,
        )
        dao.upsertSale(updatedParent)
        enqueueOutboxSet(saleCol, updatedParent.id, SarimWebSchema.saleToMap(updatedParent))
        allocList += a.copy(full = full)
      }
      if (allocList.isNotEmpty()) {
        val totalAlloc = allocList.sumOf { it.amount }
        val cashPaid = minOf(amountReceived, totalAlloc)
        cashLeftForCollection = SarimLinkEngine.round2((amountReceived - cashPaid).coerceAtLeast(0.0))
        expAllocated = SarimLinkEngine.round2((totalAlloc - cashPaid).coerceAtLeast(0.0))
      }
    }

    val totalValue = when (mode) {
      "SALE" -> SarimLinkEngine.round2(quantityKg * pricePerKg)
      "COLLECTION" -> if (allocList.isNotEmpty()) cashLeftForCollection else amountReceived
      else -> amountReceived
    }
    val totalCost = if (mode == "SALE") SarimLinkEngine.round2(quantityKg * costPerKg) else 0.0
    val profit = when (mode) {
      "SALE" -> SarimLinkEngine.round2(totalValue - totalCost)
      "COLLECTION" -> totalValue
      else -> 0.0
    }

    val currentCredBefore = cust?.currentCredit ?: 0.0
    val updatedCredit = when (mode) {
      "SALE" -> if (effectivePaymentType == "CREDIT") currentCredBefore + totalValue else currentCredBefore
      "COLLECTION" -> currentCredBefore - (amountReceived + expVal)
      "MONEY_OUT" -> currentCredBefore + amountReceived
      else -> currentCredBefore
    }

    if (cust != null) {
      val updatedCust = cust.copy(
        currentCredit = updatedCredit,
        totalQuantityKg = cust.totalQuantityKg + if (mode == "SALE") quantityKg else 0.0,
        totalSaleValue = cust.totalSaleValue + if (mode == "SALE") totalValue else 0.0,
        totalReceived = cust.totalReceived + if (mode == "COLLECTION") amountReceived else if (mode == "SALE" && effectivePaymentType == "CASH") totalValue else 0.0,
        syncPending = true,
        updatedAt = now,
      )
      dao.upsertCustomer(updatedCust)
      val custCol = if (updatedCust.isRepCustomer) SarimWebSchema.COL_CLIENTS else SarimWebSchema.COL_CUSTOMERS
      enqueueOutboxSet(custCol, updatedCust.id, SarimWebSchema.customerToMap(updatedCust))
    }

    if (mode == "COLLECTION" && expiredQtyKg > 0.0) {
      val chora = dao.getAllRawMaterials().firstOrNull { it.name.equals("CHORA", ignoreCase = true) && !it.isDeleted }
      if (chora != null) {
        val updatedChora = chora.copy(
          stockKg = chora.stockKg + expiredQtyKg,
          collectedKg = chora.collectedKg + expiredQtyKg,
          syncPending = true,
          updatedAt = now,
        )
        dao.upsertRawMaterial(updatedChora)
        enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updatedChora.id, SarimWebSchema.materialToMap(updatedChora))
      }
    }

    val ledgerEligible = effectiveAddToLedger && (
      (mode == "SALE" && effectivePaymentType == "CASH") ||
        (mode == "COLLECTION" && amountReceived > 0.0) ||
        (mode == "MONEY_OUT" && amountReceived > 0.0)
      )
    val ledgerId = linkedLedgerId?.takeIf { it.isNotBlank() } ?: if (ledgerEligible) {
      existing?.ledgerId ?: SarimUuid.generateUUID(SarimUuid.PREFIX_DAILY_LEDGER, tsMs = now)
    } else null

    val history = existing?.let { JsonCodecs.decodeEditHistory(it.editHistoryJson) }?.toMutableList() ?: mutableListOf()
    if (existing != null) {
      val changes = SarimLinkEngine.buildEditDiffs(
        listOf(
          QuadrupleDiff("dateStr", "Date", existing.dateStr, dateStr),
          QuadrupleDiff("mode", "Mode", existing.mode, mode),
          QuadrupleDiff("customerName", "Customer", existing.customerName, effectiveCustomerName),
          QuadrupleDiff("store", "Store", existing.storeName, store?.name ?: ""),
          QuadrupleDiff("quantityKg", "Qty (kg)", existing.quantityKg.toString(), quantityKg.toString()),
          QuadrupleDiff("paymentType", "Payment", existing.paymentType, effectivePaymentType),
          QuadrupleDiff("amountReceived", "Received", existing.amountReceived.toString(), amountReceived.toString()),
          QuadrupleDiff("expiredQtyKg", "Expired (kg)", existing.expiredQtyKg.toString(), expiredQtyKg.toString()),
          QuadrupleDiff("returnedQtyKg", "Returned (kg)", existing.returnedQtyKg.toString(), returnedQtyKg.toString()),
          QuadrupleDiff("notes", "Notes", existing.notes, notes.trim()),
        )
      )
      history.add(0, EditHistoryEntry(now, "Edited $mode · $effectiveCustomerName", by = actor, changes = changes))
    }

    val saleEntity = SaleTransactionEntity(
      id = txId,
      dateEpoch = dateEpoch,
      mode = mode,
      customerId = cust?.id ?: "",
      customerName = effectiveCustomerName,
      repName = cleanRep,
      storeKey = store?.storeKey ?: "",
      storeName = store?.name ?: "",
      quantityKg = quantityKg,
      pricePerKg = pricePerKg,
      totalValue = totalValue,
      totalCost = totalCost,
      profit = profit,
      paymentType = effectivePaymentType,
      amountReceived = amountReceived,
      expiredQtyKg = expiredQtyKg,
      returnedQtyKg = returnedQtyKg,
      balanceAfter = updatedCredit,
      addToLedger = ledgerEligible,
      isRepSale = isRepSale,
      latitude = lat,
      longitude = lng,
      dateStr = dateStr,
      supplyDate = dateStr,
      timeStr = existing?.timeStr?.takeIf { it.isNotBlank() } ?: SarimFormat.formatTime(now),
      customerPhone = cust?.phone ?: "",
      creditReceived = (effectivePaymentType == "CASH" || mode == "COLLECTION"),
      isCollection = (mode == "COLLECTION"),
      ledgerId = ledgerId,
      notes = notes.trim(),
      transactionType = existing?.transactionType ?: mode,
      costSource = costSource ?: if (mode == "SALE" && store != null) "store" else existing?.costSource,
      affectsInventory = !isRepSale && mode == "SALE",
      isMoneyOut = (mode == "MONEY_OUT"),
      isRepReturn = false,
      expiredValue = expVal,
      expiredUnitPrice = expUnitPrice,
      expiredAllocated = expAllocated,
      collectedAmount = if (mode == "COLLECTION" || mode == "MONEY_OUT") amountReceived else 0.0,
      allocationsJson = JsonCodecs.encodeAllocations(allocList),
      partialPaymentReceived = existing?.partialPaymentReceived ?: 0.0,
      collectionAllocsJson = existing?.collectionAllocsJson ?: "[]",
      editHistoryJson = JsonCodecs.encodeEditHistory(history.take(100)),
      createdAt = existing?.createdAt ?: now,
      updatedAt = now,
    )
    dao.upsertSale(saleEntity)
    enqueueOutboxSet(saleCol, saleEntity.id, SarimWebSchema.saleToMap(saleEntity))

    if (linkedLedgerId == null && ledgerEligible && ledgerId != null) {
      val ledgerType = if (mode == "MONEY_OUT") "OUT" else "IN"
      val amt = if (mode == "SALE") totalValue else amountReceived
      if (amt > 0.0) {
        dao.upsertLedgerEntry(
          DailyLedgerEntity(
            id = ledgerId,
            dateEpoch = dateEpoch,
            type = ledgerType,
            amount = amt,
            mode = "Cash",
            party = effectiveCustomerName,
            note = "$mode · ${store?.name ?: ""}".trim(' ', '·'),
            category = if (mode == "SALE") "Cash Sale" else if (mode == "COLLECTION") "Collection" else "Customer Payout",
            sourceType = "SALE",
            sourceId = txId,
            dateStr = dateStr,
            linkJson = JsonCodecs.encodeLedgerLink(mapOf("saleId" to txId, "isRep" to isRepSale)),
            updatedAt = now,
          )
        )
        touchTimestampKey("daily_ledger_timestamp", SarimWebSchema.COL_DAILY_LEDGER, now)
      }
    }
    return saleEntity
  }

  suspend fun saveRepReturn(
    editingId: String?,
    dateEpoch: Long,
    repName: String,
    expiredQtyKg: Double,
    returnedQtyKg: Double,
    fallbackUnitPrice: Double = 400.0,
    actor: String = "Admin",
  ): SaleTransactionEntity {
    val cleanRep = repName.trim()
    require(cleanRep.isNotBlank() && !cleanRep.equals("Direct", true)) {
      "Select a sales representative."
    }
    require(expiredQtyKg >= 0.0 && returnedQtyKg >= 0.0 && (expiredQtyKg + returnedQtyKg) > 0.0) {
      "Enter an expired and/or returned quantity."
    }
    val now = System.currentTimeMillis()
    val allSales = dao.getAllSales()
    val allSettlements = dao.getAllSettlements()
    val reconciledReturns = SarimLinkEngine.reconciledRepReturnIds(allSettlements)
    val existing = editingId?.let { id -> allSales.firstOrNull { it.id == id && !it.isDeleted } }
    if (existing != null && existing.id in reconciledReturns) {
      throw IllegalStateException("This return is already reconciled in the Calculator, so it can no longer be edited.")
    }

    val pendingDeliveries = SarimLinkEngine.getPendingRepDeliveries(cleanRep, allSales, allSettlements)
    val pendingDeliveredKg = SarimLinkEngine.round2(pendingDeliveries.sumOf { it.quantityKg })
    val alreadyPendingKg = SarimLinkEngine.round2(
      SarimLinkEngine.getPendingRepReturns(cleanRep, allSales, allSettlements)
        .filter { it.id != editingId }
        .sumOf { it.expiredQtyKg + it.returnedQtyKg }
    )
    if (SarimLinkEngine.round2(alreadyPendingKg + expiredQtyKg + returnedQtyKg) > pendingDeliveredKg + 0.0001) {
      throw IllegalStateException(
        "$cleanRep only has $pendingDeliveredKg kg awaiting reconciliation ($alreadyPendingKg kg already pending return)."
      )
    }

    val txId = editingId ?: SarimUuid.generateUUID(SarimUuid.PREFIX_SALE, tsMs = now)
    val dateStr = SarimFormat.dateInput(dateEpoch)
    val repUnitPrice = SarimLinkEngine.goodsCreditUnitPrice(cleanRep, allSales, fallbackUnitPrice, isRepProfile = false, excludeSaleId = txId)
    val repOwed = SarimLinkEngine.partyOutstanding(cleanRep, allSales, isRepProfile = false, settlements = allSettlements, excludeSaleId = txId)
    val creditPlan = SarimLinkEngine.planGoodsCredit(expiredQtyKg + returnedQtyKg, repUnitPrice, repOwed)
    val history = existing?.let { JsonCodecs.decodeEditHistory(it.editHistoryJson) }?.toMutableList() ?: mutableListOf()
    if (existing != null) {
      val changes = SarimLinkEngine.buildEditDiffs(
        listOf(
          QuadrupleDiff("expiredQtyKg", "Expired (kg)", existing.expiredQtyKg.toString(), SarimLinkEngine.round2(expiredQtyKg).toString()),
          QuadrupleDiff("returnedQtyKg", "Returned (kg)", existing.returnedQtyKg.toString(), SarimLinkEngine.round2(returnedQtyKg).toString()),
        )
      )
      history.add(0, EditHistoryEntry(now, "Edited REP_RETURN · $cleanRep", by = actor, changes = changes))
    }

    val entity = SaleTransactionEntity(
      id = txId,
      dateEpoch = existing?.dateEpoch ?: dateEpoch,
      mode = "REP_RETURN",
      customerId = "",
      customerName = cleanRep,
      repName = cleanRep,
      storeKey = "",
      storeName = "",
      quantityKg = 0.0,
      pricePerKg = 0.0,
      totalValue = 0.0,
      totalCost = 0.0,
      profit = 0.0,
      paymentType = "REP_RETURN",
      amountReceived = 0.0,
      expiredQtyKg = SarimLinkEngine.round2(expiredQtyKg),
      returnedQtyKg = SarimLinkEngine.round2(returnedQtyKg),
      returnValue = creditPlan.value,
      returnUnitPrice = if (creditPlan.value > 0.0) repUnitPrice else 0.0,
      balanceAfter = (repOwed - creditPlan.value).coerceAtLeast(0.0),
      addToLedger = false,
      isRepSale = false,
      dateStr = existing?.dateStr ?: dateStr,
      supplyDate = existing?.supplyDate ?: dateStr,
      timeStr = existing?.timeStr?.takeIf { it.isNotBlank() } ?: SarimFormat.formatTime(now),
      creditReceived = true,
      isCollection = false,
      affectsInventory = false,
      isMoneyOut = false,
      isRepReturn = true,
      transactionType = "REP_RETURN",
      editHistoryJson = JsonCodecs.encodeEditHistory(history.take(100)),
      createdAt = existing?.createdAt ?: now,
      updatedAt = now,
    )
    dao.upsertSale(entity)
    enqueueOutboxSet(SarimWebSchema.COL_SALES, entity.id, SarimWebSchema.saleToMap(entity))
    return entity
  }

  suspend fun deleteSale(id: String) {
    val now = System.currentTimeMillis()
    val sale = dao.getAllSales().firstOrNull { it.id == id }
    if (sale != null) {
      SarimLinkEngine.getSaleBlockReason(sale, dao.getAllSettlements())?.let { msg ->
        throw IllegalStateException(msg)
      }
      SarimLinkEngine.getCollectionDeleteBlockReason(sale, dao.getAllRawMaterials())?.let { msg ->
        throw IllegalStateException(msg)
      }
      applySaleReversalOnDeleteOrEdit(sale, now)
    }
    dao.softDeleteSale(id, now)
    val targetCol = if (sale?.isRepSale == true) SarimWebSchema.COL_REP else SarimWebSchema.COL_SALES
    val dispAmt = when {
      sale == null -> 0.0
      sale.mode == "COLLECTION" || sale.mode == "MONEY_OUT" -> sale.amountReceived.takeIf { it > 0.0 } ?: sale.totalValue
      sale.isRepReturn -> sale.returnValue
      else -> sale.totalValue
    }
    enqueueOutboxDelete(
      collection = targetCol,
      docId = id,
      title = "${sale?.mode ?: "SALE"}: ${sale?.customerName ?: id}",
      subtitle = if (sale?.isRepReturn == true) "${sale.expiredQtyKg} exp / ${sale.returnedQtyKg} ret kg" else "${sale?.quantityKg ?: 0.0} kg",
      amountText = "Rs $dispAmt",
      snapshotMap = sale?.let { SarimWebSchema.saleToMap(it) },
    )
  }

  suspend fun savePaymentEntity(
    editingId: String?,
    name: String,
    phone: String,
    wallet: String,
    ledgerPage: String,
    openingType: String,
    openingAmount: Double,
    photoUri: String,
    address: String = "",
    category: String = "",
    notes: String = "",
    actor: String = "Admin",
  ): PaymentEntityRecord {
    val now = System.currentTimeMillis()
    val existing = editingId?.let { id -> dao.getAllPaymentEntities().firstOrNull { it.id == id } }
    val cleanName = name.trim()
    val cleanPhone = phone.trim()
    val cleanWallet = wallet.trim()
    val cleanPage = ledgerPage.trim().take(12)
    val resolvedAddress = address.trim().ifBlank { existing?.address ?: "" }
    val resolvedCategory = category.trim().ifBlank { existing?.category ?: "" }
    val resolvedNotes = notes.trim().ifBlank { existing?.notes ?: "" }
    val signedOpening = if (openingType == "CREDIT") openingAmount else -openingAmount
    val prevSigned = existing?.let { if (it.openingType == "CREDIT") it.openingAmount else -it.openingAmount } ?: 0.0
    val delta = signedOpening - prevSigned
    val history = existing?.let { JsonCodecs.decodeEditHistory(it.editHistoryJson) }?.toMutableList() ?: mutableListOf()
    if (existing != null) {
      val changes = SarimLinkEngine.buildEditDiffs(
        listOf(
          QuadrupleDiff("name", "Name", existing.name, cleanName),
          QuadrupleDiff("phone", "Phone", existing.phone, cleanPhone),
          QuadrupleDiff("wallet", "Wallet", existing.wallet, cleanWallet),
          QuadrupleDiff("ledgerPage", "Ledger Page", existing.ledgerPage, cleanPage),
          QuadrupleDiff("openingType", "Opening Type", existing.openingType, openingType),
          QuadrupleDiff("openingAmount", "Opening Balance", existing.openingAmount.toString(), openingAmount.toString()),
          QuadrupleDiff("address", "Address", existing.address, resolvedAddress),
          QuadrupleDiff("category", "Category", existing.category, resolvedCategory),
          QuadrupleDiff("notes", "Notes", existing.notes, resolvedNotes),
        )
      )
      history.add(0, EditHistoryEntry(now, "Edited entity $cleanName", by = actor, changes = changes))
    }
    val rec = (existing ?: PaymentEntityRecord(
      id = SarimUuid.generateUUID(SarimUuid.PREFIX_ENT, tsMs = now),
      name = cleanName,
      createdAt = now,
    )).copy(
      name = cleanName,
      phone = cleanPhone,
      wallet = cleanWallet,
      ledgerPage = cleanPage,
      openingType = openingType,
      openingAmount = openingAmount,
      currentBalance = (existing?.currentBalance ?: 0.0) + delta,
      photoUri = photoUri,
      address = resolvedAddress,
      category = resolvedCategory,
      notes = resolvedNotes,
      editHistoryJson = JsonCodecs.encodeEditHistory(history.take(100)),
      type = if (openingType == "DEBIT") "payor" else "payee",
      syncPending = true,
      updatedAt = now,
    )
    dao.upsertPaymentEntity(rec)
    enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, rec.id, SarimWebSchema.entityToMap(rec))

    // Propagate entity rename across transactions, expenses, payment transfers, and inventory suppliers (matches web planEntityRename)
    if (existing != null && !existing.name.equals(rec.name, ignoreCase = true)) {
      dao.getAllPaymentTransactions().filter { !it.isDeleted && it.entityId == rec.id }.forEach { tx ->
        val updatedTx = tx.copy(entityOrExpenseName = rec.name, syncPending = true, updatedAt = now)
        dao.upsertPaymentTransaction(updatedTx)
        enqueueOutboxSet(SarimWebSchema.COL_TRANSACTIONS, updatedTx.id, SarimWebSchema.transactionToMap(updatedTx))
        tx.expenseId?.let { expId ->
          dao.getAllExpenses().firstOrNull { it.id == expId && !it.isDeleted }?.let { exp ->
            val updatedExp = exp.copy(name = rec.name, syncPending = true, updatedAt = now)
            dao.upsertExpense(updatedExp)
            enqueueOutboxSet(SarimWebSchema.COL_EXPENSES, updatedExp.id, SarimWebSchema.expenseToMap(updatedExp))
          }
        }
        tx.ledgerId?.takeIf { it.isNotBlank() }?.let { lid ->
          dao.getAllDailyLedger().firstOrNull { it.id == lid && !it.isDeleted }?.let { led ->
            dao.upsertLedgerEntry(led.copy(party = rec.name, updatedAt = now))
            touchTimestampKey("daily_ledger_timestamp", SarimWebSchema.COL_DAILY_LEDGER, now)
          }
        }
      }
      dao.getAllRawMaterials().filter { !it.isDeleted && it.supplierId == rec.id }.forEach { mat ->
        val updatedMat = mat.copy(supplierName = rec.name, syncPending = true, updatedAt = now)
        dao.upsertRawMaterial(updatedMat)
        enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updatedMat.id, SarimWebSchema.materialToMap(updatedMat))
      }
      dao.getAllPurchases().filter { !it.isDeleted && it.supplierId == rec.id }.forEach { lot ->
        val updatedLot = lot.copy(supplierName = rec.name, syncPending = true, updatedAt = now)
        dao.upsertPurchase(updatedLot)
        enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updatedLot.id, SarimWebSchema.purchaseLotToMap(updatedLot))
      }
    }

    val pKey = "entity:${rec.id}"
    if (photoUri.isNotBlank()) {
      upsertPhotoKey(pKey, photoUri, now)
    } else if (existing?.photoUri?.isNotBlank() == true) {
      markPhotoDeleted(pKey, now)
    }
    return rec
  }

  private suspend fun assertPaymentTxCashDeleteAllowed(tx: PaymentTransactionEntity) {
    SarimLinkEngine.getPaymentTxDeleteBlockReason(
      tx = tx,
      allTxs = dao.getAllPaymentTransactions(),
      sales = dao.getAllSales(),
      settlements = dao.getAllSettlements(),
      factoryBatches = dao.getAllFactoryBatches(),
    )?.let { msg -> throw IllegalStateException(msg) }
  }

  suspend fun deletePaymentEntity(id: String) {
    val now = System.currentTimeMillis()
    val e = dao.getAllPaymentEntities().firstOrNull { it.id == id }
    val groupId = SarimUuid.generateUUID("${SarimUuid.PREFIX_GRP}_ent", tsMs = now)

    val allTxs = dao.getAllPaymentTransactions()
    val linkedTxs = allTxs.filter { !it.isDeleted && it.entityId == id }
    if (e != null) {
      SarimLinkEngine.getEntityDeleteBlockReason(
        entity = e,
        allTxs = allTxs,
        sales = dao.getAllSales(),
        settlements = dao.getAllSettlements(),
        factoryBatches = dao.getAllFactoryBatches(),
      )?.let { msg -> throw IllegalStateException(msg) }
    }

    // Unlink supplier from raw materials & purchase lots
    dao.getAllRawMaterials().filter { !it.isDeleted && it.supplierId == id }.forEach { mat ->
      val updatedMat = mat.copy(supplierId = null, supplierName = null, syncPending = true, updatedAt = now)
      dao.upsertRawMaterial(updatedMat)
      enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updatedMat.id, SarimWebSchema.materialToMap(updatedMat))
    }
    dao.getAllPurchases().filter { !it.isDeleted && it.supplierId == id }.forEach { lot ->
      val updatedLot = lot.copy(supplierId = null, supplierName = null, syncPending = true, updatedAt = now)
      dao.upsertPurchase(updatedLot)
      enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updatedLot.id, SarimWebSchema.purchaseLotToMap(updatedLot))
    }

    // Cascade delete linked payment transactions + expenses + ledger + photos
    linkedTxs.forEach { tx ->
      tx.ledgerId?.takeIf { it.isNotBlank() }?.let { lid ->
        dao.softDeleteLedgerEntry(lid, now)
        touchTimestampKey("daily_ledger_timestamp", SarimWebSchema.COL_DAILY_LEDGER, now)
      }
      tx.expenseId?.let { expId ->
        val exp = dao.getAllExpenses().firstOrNull { it.id == expId }
        dao.softDeleteExpense(expId, now)
        markPhotoDeleted("expense:$expId", now)
        enqueueOutboxDelete(
          collection = SarimWebSchema.COL_EXPENSES,
          docId = expId,
          title = tx.entityOrExpenseName,
          subtitle = "Expense",
          amountText = "Rs ${tx.amount}",
          snapshotMap = exp?.let { SarimWebSchema.expenseToMap(it) },
          groupId = groupId,
        )
      }
      dao.softDeletePaymentTransaction(tx.id, now)
      enqueueOutboxDelete(
        collection = SarimWebSchema.COL_TRANSACTIONS,
        docId = tx.id,
        title = "${tx.type}: ${tx.entityOrExpenseName}",
        subtitle = tx.description,
        amountText = "Rs ${tx.amount}",
        snapshotMap = SarimWebSchema.transactionToMap(tx),
        groupId = groupId,
      )
    }

    // Cascade delete paired payment transfers involving this entity
    dao.getAllPaymentTransfers().filter { !it.isDeleted && (it.fromEntityId == id || it.toEntityId == id) }.forEach { tr ->
      deletePaymentTransfer(tr.id, groupIdOverride = groupId)
    }

    if (e != null) forgetLedgerParty(e.name, now)
    markPhotoDeleted("entity:$id", now)
    dao.softDeletePaymentEntity(id, now)
    enqueueOutboxDelete(
      collection = SarimWebSchema.COL_ENTITIES,
      docId = id,
      title = e?.name ?: "Entity",
      subtitle = e?.phone ?: "",
      amountText = "Rs ${e?.currentBalance ?: 0.0}",
      snapshotMap = e?.let { SarimWebSchema.entityToMap(it) },
      groupId = groupId,
    )
  }

  private suspend fun revertPaymentTransactionEffects(tx: PaymentTransactionEntity, now: Long) {
    if (!tx.entityId.isNullOrBlank()) {
      dao.getAllPaymentEntities().firstOrNull { it.id == tx.entityId && !it.isDeleted }?.let { ent ->
        val reversedBal = if (tx.type == "PAYMENT_IN") ent.currentBalance - tx.amount else ent.currentBalance + tx.amount
        val updatedEnt = ent.copy(currentBalance = reversedBal, syncPending = true, updatedAt = now)
        dao.upsertPaymentEntity(updatedEnt)
        enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, updatedEnt.id, SarimWebSchema.entityToMap(updatedEnt))
      }
    }

    val consumed = JsonCodecs.decodeConsumedCredits(tx.consumedCreditsJson)
    var restoredCreditTotal = 0.0
    if (consumed.isNotEmpty()) {
      val allPay = dao.getAllPaymentTransactions()
      consumed.forEach { item ->
        allPay.firstOrNull { it.id == item.id && !it.isDeleted }?.let { inTx ->
          val addAmt = if (item.amount > 0.0) item.amount else tx.amount
          restoredCreditTotal += addAmt
          val updatedIn = inTx.copy(
            supplierCreditAmount = inTx.supplierCreditAmount + addAmt,
            syncPending = true,
            updatedAt = now,
          )
          dao.upsertPaymentTransaction(updatedIn)
          enqueueOutboxSet(SarimWebSchema.COL_TRANSACTIONS, updatedIn.id, SarimWebSchema.transactionToMap(updatedIn))
        }
      }
    }

    val remainingToRestore = (tx.amount - restoredCreditTotal).coerceAtLeast(0.0)
    if (tx.isPayable && tx.type == "PAYMENT_OUT" && remainingToRestore > 0.001) {
      val matIds = (JsonCodecs.decodeStrings(tx.materialIdsJson) + listOfNotNull(tx.materialId)).distinct()
      var remRestore = remainingToRestore
      val matchingLots = dao.getAllPurchases()
        .filter { !it.isDeleted && (it.materialId in matIds || (!tx.entityId.isNullOrBlank() && it.supplierId == tx.entityId)) }
        .sortedByDescending { it.dateEpoch }
      val matDeltaMap = linkedMapOf<String, Double>()
      for (lot in matchingLots) {
        if (remRestore <= 0.001) break
        val cap = (lot.totalAmount - lot.totalPayable).coerceAtLeast(0.0)
        if (cap <= 0.001) continue
        val addBack = minOf(remRestore, cap)
        remRestore -= addBack
        val updatedLot = lot.copy(
          totalPayable = lot.totalPayable + addBack,
          paymentStatus = "pending",
          syncPending = true,
          updatedAt = now,
        )
        dao.upsertPurchase(updatedLot)
        enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updatedLot.id, SarimWebSchema.purchaseLotToMap(updatedLot))
        matDeltaMap[lot.materialId] = (matDeltaMap[lot.materialId] ?: 0.0) + addBack
      }
      if (remRestore > 0.001 && matIds.isNotEmpty()) {
        matDeltaMap[matIds.first()] = (matDeltaMap[matIds.first()] ?: 0.0) + remRestore
      }
      matDeltaMap.forEach { (matId, delta) ->
        if (delta <= 0.001) return@forEach
        dao.getAllRawMaterials().firstOrNull { !it.isDeleted && it.id == matId }?.let { mat ->
          val updatedMat = mat.copy(
            pendingSupplierAmount = mat.pendingSupplierAmount + delta,
            paymentStatus = "pending",
            syncPending = true,
            updatedAt = now,
          )
          dao.upsertRawMaterial(updatedMat)
          enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updatedMat.id, SarimWebSchema.materialToMap(updatedMat))
        }
      }
    }
  }

  suspend fun savePaymentTransaction(
    editingId: String?,
    dateEpoch: Long,
    type: String, // EXPENSE, PAYMENT_IN, PAYMENT_OUT
    name: String,
    amount: Double,
    description: String,
    photoUri: String,
    addToLedger: Boolean,
    category: String? = null,
    linkedLedgerId: String? = null,
    actor: String = "Admin",
  ): PaymentTransactionEntity {
    val now = System.currentTimeMillis()
    val cleanName = name.trim()
    val dateStr = SarimFormat.dateInput(dateEpoch)
    val existing = editingId?.let { id -> dao.getAllPaymentTransactions().firstOrNull { it.id == id } }
    val consumedOnExistingIn = if (existing?.type == "PAYMENT_IN") {
      (existing.amount - existing.supplierCreditAmount).coerceAtLeast(0.0)
    } else 0.0
    if (existing != null) {
      revertPaymentTransactionEffects(existing, now)
    }

    if (type == "EXPENSE") {
      appendCategoryToKv("categories", category?.takeIf { it.isNotBlank() } ?: cleanName, now)
    }

    val entity = if (type != "EXPENSE") {
      dao.getAllPaymentEntities().firstOrNull { !it.isDeleted && it.name.equals(cleanName, ignoreCase = true) }
        ?: savePaymentEntity(null, cleanName, "", "", "", "CREDIT", 0.0, "")
    } else null

    if (entity != null) {
      val nextImpact = if (type == "PAYMENT_IN") amount else -amount
      val newBal = entity.currentBalance + nextImpact
      val updatedEnt = entity.copy(currentBalance = newBal, syncPending = true, updatedAt = now)
      dao.upsertPaymentEntity(updatedEnt)
      enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, updatedEnt.id, SarimWebSchema.entityToMap(updatedEnt))
    }

    // FIFO supplier payable settlement + consumed supplier credit for PAYMENT_OUT
    val settledMatIds = mutableListOf<String>()
    val consumedCredits = mutableListOf<JsonCodecs.ConsumedCreditItem>()
    var isPayableOut = false
    var supplierCreditAmt = 0.0
    if (type == "PAYMENT_OUT" && entity != null) {
      var remainingPay = amount
      val openLots = dao.getAllPurchases()
        .filter { !it.isDeleted && it.supplierId == entity.id && it.totalPayable > 0.001 }
        .sortedBy { it.dateEpoch }
      for (lot in openLots) {
        if (remainingPay <= 0.001) break
        val take = minOf(remainingPay, lot.totalPayable)
        remainingPay -= take
        val lotLeft = (lot.totalPayable - take).coerceAtLeast(0.0)
        val updatedLot = lot.copy(
          totalPayable = lotLeft,
          paymentStatus = if (lotLeft <= 0.001) "paid" else "pending",
          syncPending = true,
          updatedAt = now,
        )
        dao.upsertPurchase(updatedLot)
        enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updatedLot.id, SarimWebSchema.purchaseLotToMap(updatedLot))

        dao.getAllRawMaterials().firstOrNull { it.id == lot.materialId && !it.isDeleted }?.let { mat ->
          val matLeft = (mat.pendingSupplierAmount - take).coerceAtLeast(0.0)
          val updatedMat = mat.copy(
            pendingSupplierAmount = matLeft,
            paymentStatus = if (matLeft <= 0.001) "paid" else "pending",
            syncPending = true,
            updatedAt = now,
          )
          dao.upsertRawMaterial(updatedMat)
          enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updatedMat.id, SarimWebSchema.materialToMap(updatedMat))
        }
        settledMatIds += lot.materialId
        isPayableOut = true
      }
      if (!isPayableOut) {
        val openMats = dao.getAllRawMaterials()
          .filter { !it.isDeleted && it.supplierId == entity.id && it.pendingSupplierAmount > 0.001 }
        for (mat in openMats) {
          if (remainingPay <= 0.001) break
          val take = minOf(remainingPay, mat.pendingSupplierAmount)
          remainingPay -= take
          val matLeft = (mat.pendingSupplierAmount - take).coerceAtLeast(0.0)
          val updatedMat = mat.copy(
            pendingSupplierAmount = matLeft,
            paymentStatus = if (matLeft <= 0.001) "paid" else "pending",
            syncPending = true,
            updatedAt = now,
          )
          dao.upsertRawMaterial(updatedMat)
          enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updatedMat.id, SarimWebSchema.materialToMap(updatedMat))
          settledMatIds += mat.id
          isPayableOut = true
        }
      }
      if (remainingPay > 0.001) {
        val allEntityIn = dao.getAllPaymentTransactions()
          .filter { !it.isDeleted && it.entityId == entity.id && it.type == "PAYMENT_IN" }
        val toConsume = SarimLinkEngine.planSupplierCreditConsumption(remainingPay, allEntityIn)
        toConsume.forEach { item ->
          allEntityIn.firstOrNull { it.id == item.id }?.let { inTx ->
            val updatedIn = inTx.copy(
              supplierCreditAmount = (inTx.supplierCreditAmount - item.amount).coerceAtLeast(0.0),
              syncPending = true,
              updatedAt = now,
            )
            dao.upsertPaymentTransaction(updatedIn)
            enqueueOutboxSet(SarimWebSchema.COL_TRANSACTIONS, updatedIn.id, SarimWebSchema.transactionToMap(updatedIn))
            consumedCredits += item
            isPayableOut = true
          }
        }
      }
    } else if (type == "PAYMENT_IN" && entity != null) {
      val hasSupMaterials = entity.isSupplier || dao.getAllRawMaterials().any { !it.isDeleted && it.supplierId == entity.id }
      if (hasSupMaterials) {
        supplierCreditAmt = (amount - consumedOnExistingIn).coerceAtLeast(0.0)
        isPayableOut = true
      }
    }

    val txId = editingId ?: SarimUuid.generateUUID(SarimUuid.PREFIX_PAY, tsMs = now)
    val ledgerId = linkedLedgerId?.takeIf { it.isNotBlank() }
      ?: if (addToLedger && amount > 0.0) (existing?.ledgerId ?: SarimUuid.generateUUID(SarimUuid.PREFIX_DAILY_LEDGER, tsMs = now)) else null
    val expId = existing?.expenseId ?: SarimUuid.generateUUID(SarimUuid.PREFIX_EXP, tsMs = now)

    val expenseRec = ExpenseRecordEntity(
      id = expId,
      name = cleanName,
      amount = amount,
      date = dateStr,
      dateEpoch = dateEpoch,
      description = description.trim(),
      category = when (type) {
        "PAYMENT_IN" -> "IN"
        "PAYMENT_OUT" -> "OUT"
        else -> category?.takeIf { it.isNotBlank() } ?: "operating"
      },
      ledgerId = ledgerId,
      createdAt = existing?.createdAt ?: now,
      updatedAt = now,
    )
    dao.upsertExpense(expenseRec)
    enqueueOutboxSet(SarimWebSchema.COL_EXPENSES, expenseRec.id, SarimWebSchema.expenseToMap(expenseRec))

    val history = existing?.let { JsonCodecs.decodeEditHistory(it.editHistoryJson) }?.toMutableList() ?: mutableListOf()
    if (existing != null) {
      val resolvedCat = if (type == "EXPENSE") (category?.takeIf { it.isNotBlank() } ?: cleanName) else ""
      val changes = SarimLinkEngine.buildEditDiffs(
        listOf(
          QuadrupleDiff("dateStr", "Date", existing.dateStr, dateStr),
          QuadrupleDiff("type", "Type", existing.type, type),
          QuadrupleDiff("entityOrExpenseName", "Party", existing.entityOrExpenseName, cleanName),
          QuadrupleDiff("amount", "Amount", existing.amount.toString(), amount.toString()),
          QuadrupleDiff("category", "Category", existing.category ?: "", resolvedCat),
          QuadrupleDiff("description", "Description", existing.description, description.trim()),
        )
      )
      history.add(0, EditHistoryEntry(now, "Edited $type · $cleanName", by = actor, changes = changes))
    }

    val txEntity = PaymentTransactionEntity(
      id = txId,
      dateEpoch = dateEpoch,
      type = type,
      entityOrExpenseName = cleanName,
      entityId = entity?.id,
      amount = amount,
      description = description.trim(),
      photoUri = photoUri,
      addToLedger = addToLedger || !linkedLedgerId.isNullOrBlank(),
      dateStr = dateStr,
      timeStr = existing?.timeStr?.takeIf { it.isNotBlank() } ?: SarimFormat.formatTime(now),
      isExpense = (type == "EXPENSE"),
      expenseId = expenseRec.id,
      isPayable = isPayableOut,
      ledgerId = ledgerId,
      entityType = entity?.type ?: if (type == "EXPENSE") "expense" else "",
      materialId = settledMatIds.firstOrNull(),
      materialIdsJson = JsonCodecs.encodeStrings(settledMatIds.distinct()),
      consumedCreditsJson = JsonCodecs.encodeConsumedCredits(consumedCredits),
      category = if (type == "EXPENSE") (category?.takeIf { it.isNotBlank() } ?: cleanName) else null,
      supplierCreditAmount = supplierCreditAmt,
      editHistoryJson = JsonCodecs.encodeEditHistory(history.take(100)),
      createdAt = existing?.createdAt ?: now,
      updatedAt = now,
    )
    dao.upsertPaymentTransaction(txEntity)
    enqueueOutboxSet(SarimWebSchema.COL_TRANSACTIONS, txEntity.id, SarimWebSchema.transactionToMap(txEntity))

    val pKey = "expense:${expenseRec.id}"
    if (photoUri.isNotBlank()) {
      upsertPhotoKey(pKey, photoUri, now)
    } else if (existing?.photoUri?.isNotBlank() == true) {
      markPhotoDeleted(pKey, now)
    }

    if (linkedLedgerId == null) {
      if (addToLedger && ledgerId != null) {
        dao.upsertLedgerEntry(
          DailyLedgerEntity(
            id = ledgerId,
            dateEpoch = dateEpoch,
            type = if (type == "PAYMENT_IN") "IN" else "OUT",
            amount = amount,
            mode = "Cash",
            party = cleanName,
            note = description.trim(),
            category = if (type == "EXPENSE") (category?.takeIf { it.isNotBlank() } ?: cleanName) else type,
            sourceType = "PAYMENT",
            sourceId = txId,
            dateStr = dateStr,
            linkJson = JsonCodecs.encodeLedgerLink(mapOf("txId" to txId, "expenseId" to expenseRec.id)),
            updatedAt = now,
          )
        )
        touchTimestampKey("daily_ledger_timestamp", SarimWebSchema.COL_DAILY_LEDGER, now)
      } else if (existing?.ledgerId != null) {
        dao.softDeleteLedgerEntry(existing.ledgerId, now)
        touchTimestampKey("daily_ledger_timestamp", SarimWebSchema.COL_DAILY_LEDGER, now)
      }
    }
    return txEntity
  }

  suspend fun deletePaymentTransaction(id: String) {
    val now = System.currentTimeMillis()
    val tx = dao.getAllPaymentTransactions().firstOrNull { it.id == id }
    val groupId = if (tx?.expenseId != null) SarimUuid.generateUUID("${SarimUuid.PREFIX_GRP}_pay", tsMs = now) else null

    if (tx != null) {
      assertPaymentTxCashDeleteAllowed(tx)
      revertPaymentTransactionEffects(tx, now)

      tx.ledgerId?.takeIf { it.isNotBlank() }?.let { lid ->
        dao.softDeleteLedgerEntry(lid, now)
        touchTimestampKey("daily_ledger_timestamp", SarimWebSchema.COL_DAILY_LEDGER, now)
      }

      tx.expenseId?.let { expId ->
        val exp = dao.getAllExpenses().firstOrNull { it.id == expId }
        dao.softDeleteExpense(expId, now)
        markPhotoDeleted("expense:$expId", now)
        enqueueOutboxDelete(
          collection = SarimWebSchema.COL_EXPENSES,
          docId = expId,
          title = tx.entityOrExpenseName,
          subtitle = "Expense",
          amountText = "Rs ${tx.amount}",
          snapshotMap = exp?.let { SarimWebSchema.expenseToMap(it) },
          groupId = groupId,
        )
      }
    }

    dao.softDeletePaymentTransaction(id, now)
    enqueueOutboxDelete(
      collection = SarimWebSchema.COL_TRANSACTIONS,
      docId = id,
      title = "${tx?.type ?: "PAYMENT"}: ${tx?.entityOrExpenseName ?: id}",
      subtitle = tx?.description ?: "",
      amountText = "Rs ${tx?.amount ?: 0.0}",
      snapshotMap = tx?.let { SarimWebSchema.transactionToMap(it) },
      groupId = groupId,
    )
  }

  suspend fun deleteAllExpensesByName(name: String) {
    dao.getAllPaymentTransactions()
      .filter { !it.isDeleted && it.type == "EXPENSE" && it.entityOrExpenseName.equals(name, ignoreCase = true) }
      .forEach { deletePaymentTransaction(it.id) }
  }

  suspend fun resolveLedgerTargetForInput(
    type: String,
    scope: String,
    party: String,
    category: String,
    overrideChoice: String? = null,
  ): SarimLinkEngine.LedgerLinkTarget {
    return SarimLinkEngine.resolveLedgerTarget(
      type = type,
      scope = scope,
      party = party,
      category = category,
      entities = dao.getAllPaymentEntities(),
      customers = dao.getAllCustomers(),
      sales = dao.getAllSales(),
      overrideChoice = overrideChoice,
    )
  }

  private suspend fun checkAndRemoveLedgerLink(entry: DailyLedgerEntity, now: Long, groupId: String? = null) {
    val link = JsonCodecs.decodeLedgerLink(entry.linkJson) ?: return
    val kind = link["kind"]?.toString()?.trim()?.lowercase().orEmpty()
    when (kind) {
      "entity", "expense" -> {
        val txId = link["txId"]?.toString()?.takeIf { it.isNotBlank() }
        if (txId != null) {
          val tx = dao.getAllPaymentTransactions().firstOrNull { it.id == txId && !it.isDeleted }
          if (tx != null) {
            assertPaymentTxCashDeleteAllowed(tx)
            revertPaymentTransactionEffects(tx, now)
            tx.expenseId?.let { expId ->
              val exp = dao.getAllExpenses().firstOrNull { it.id == expId }
              dao.softDeleteExpense(expId, now)
              markPhotoDeleted("expense:$expId", now)
              enqueueOutboxDelete(
                collection = SarimWebSchema.COL_EXPENSES,
                docId = expId,
                title = tx.entityOrExpenseName,
                subtitle = "Expense",
                amountText = "Rs ${tx.amount}",
                snapshotMap = exp?.let { SarimWebSchema.expenseToMap(it) },
                groupId = groupId,
              )
            }
            dao.softDeletePaymentTransaction(tx.id, now)
            enqueueOutboxDelete(
              collection = SarimWebSchema.COL_TRANSACTIONS,
              docId = tx.id,
              title = "${tx.type}: ${tx.entityOrExpenseName}",
              subtitle = tx.description,
              amountText = "Rs ${tx.amount}",
              snapshotMap = SarimWebSchema.transactionToMap(tx),
              groupId = groupId,
            )
          }
        } else {
          val expId = link["expenseId"]?.toString()?.takeIf { it.isNotBlank() }
          if (expId != null) {
            val exp = dao.getAllExpenses().firstOrNull { it.id == expId && !it.isDeleted }
            if (exp != null) {
              dao.softDeleteExpense(expId, now)
              markPhotoDeleted("expense:$expId", now)
              enqueueOutboxDelete(
                collection = SarimWebSchema.COL_EXPENSES,
                docId = expId,
                title = exp.name,
                subtitle = "Expense",
                amountText = "Rs ${exp.amount}",
                snapshotMap = SarimWebSchema.expenseToMap(exp),
                groupId = groupId,
              )
            }
          }
        }
      }
      "customer" -> {
        val saleId = link["saleId"]?.toString()?.takeIf { it.isNotBlank() }
        if (saleId != null) {
          val sale = dao.getAllSales().firstOrNull { it.id == saleId && !it.isDeleted }
          if (sale != null) {
            SarimLinkEngine.getSaleBlockReason(sale, dao.getAllSettlements())?.let { throw IllegalStateException(it) }
            SarimLinkEngine.getCollectionDeleteBlockReason(sale, dao.getAllRawMaterials())?.let { throw IllegalStateException(it) }
            applySaleReversalOnDeleteOrEdit(sale, now)
            dao.softDeleteSale(sale.id, now)
            enqueueOutboxDelete(
              collection = SarimWebSchema.COL_SALES,
              docId = sale.id,
              title = "${sale.mode}: ${sale.customerName}",
              subtitle = "${sale.quantityKg} kg",
              amountText = "Rs ${sale.amountReceived.takeIf { it > 0.0 } ?: sale.totalValue}",
              snapshotMap = SarimWebSchema.saleToMap(sale),
              groupId = groupId,
            )
          }
        }
      }
    }
  }

  private suspend fun postLedgerLink(
    entry: DailyLedgerEntity,
    target: SarimLinkEngine.LedgerLinkTarget,
  ): String {
    return when (target) {
      is SarimLinkEngine.LedgerLinkTarget.EntityTarget -> {
        val payType = if (entry.type == "IN") "PAYMENT_IN" else "PAYMENT_OUT"
        val tx = savePaymentTransaction(
          editingId = null,
          dateEpoch = entry.dateEpoch,
          type = payType,
          name = target.entity.name,
          amount = entry.amount,
          description = entry.note,
          photoUri = "",
          addToLedger = false,
          category = entry.category,
          linkedLedgerId = entry.id,
        )
        JsonCodecs.encodeLedgerLink(
          mapOf(
            "kind" to "entity",
            "entityId" to target.entity.id,
            "txId" to tx.id,
            "name" to target.entity.name,
          )
        )
      }
      is SarimLinkEngine.LedgerLinkTarget.CustomerTarget -> {
        val saleMode = if (entry.type == "OUT") "MONEY_OUT" else "COLLECTION"
        val sale = saveSaleOrCollection(
          editingId = null,
          dateEpoch = entry.dateEpoch,
          mode = saleMode,
          customerName = target.name,
          newCustomerPhone = "",
          repName = "Direct",
          store = null,
          quantityKg = 0.0,
          costPerKg = 0.0,
          paymentType = if (saleMode == "MONEY_OUT") "MONEY_OUT" else "COLLECTION",
          amountReceived = entry.amount,
          expiredQtyKg = 0.0,
          returnedQtyKg = 0.0,
          addToLedger = false,
          isRepSale = false,
          notes = entry.note,
          linkedLedgerId = entry.id,
        )
        JsonCodecs.encodeLedgerLink(
          mapOf(
            "kind" to "customer",
            "saleId" to sale.id,
            "name" to target.name,
          )
        )
      }
      is SarimLinkEngine.LedgerLinkTarget.ExpenseTarget -> {
        val tx = savePaymentTransaction(
          editingId = null,
          dateEpoch = entry.dateEpoch,
          type = "EXPENSE",
          name = target.name,
          amount = entry.amount,
          description = entry.note,
          photoUri = "",
          addToLedger = false,
          category = entry.category.ifBlank { target.name },
          linkedLedgerId = entry.id,
        )
        JsonCodecs.encodeLedgerLink(
          mapOf(
            "kind" to "expense",
            "txId" to tx.id,
            "expenseId" to (tx.expenseId ?: ""),
            "name" to target.name,
          )
        )
      }
      SarimLinkEngine.LedgerLinkTarget.None, is SarimLinkEngine.LedgerLinkTarget.AmbiguousTarget -> ""
    }
  }

  suspend fun saveDailyLedgerEntry(
    editingId: String?,
    dateEpoch: Long,
    type: String,
    amount: Double,
    mode: String,
    toMode: String,
    party: String,
    refNo: String,
    note: String,
    category: String? = null,
    scope: String = "",
    linkedTargetChoice: String? = null, // null = auto, "NONE", "ENTITY", "CUSTOMER", "EXPENSE"
  ) {
    val now = System.currentTimeMillis()
    val existing = editingId?.let { id -> dao.getAllDailyLedger().firstOrNull { it.id == id } }
    if (existing != null && SarimLinkEngine.isTabOwnedLedgerRow(existing)) {
      throw IllegalStateException(SarimLinkEngine.tabEntryNotice(existing))
    }

    val resolvedCat = category?.takeIf { it.isNotBlank() }
      ?: if (type == "TRANSFER") "Account Transfer" else if (party.isNotBlank()) party.trim() else (existing?.category ?: "General")
    val entryId = editingId ?: SarimUuid.generateUUID(SarimUuid.PREFIX_DAILY_LEDGER, tsMs = now)
    val draft = DailyLedgerEntity(
      id = entryId,
      dateEpoch = dateEpoch,
      type = type,
      amount = amount,
      mode = mode.trim().ifBlank { "Cash" },
      toMode = if (type == "TRANSFER") toMode.trim() else "",
      party = if (type == "TRANSFER") "" else party.trim(),
      refNo = refNo.trim(),
      note = note.trim(),
      category = resolvedCat,
      sourceType = "MANUAL",
      sourceId = "",
      scope = scope.ifBlank { existing?.scope ?: "" },
      dateStr = SarimFormat.dateInput(dateEpoch),
      linkJson = "",
      createdAt = existing?.createdAt ?: now,
      updatedAt = now,
    )

    if (existing != null) {
      checkAndRemoveLedgerLink(existing, now)
    }

    val resolvedTarget = SarimLinkEngine.resolveLedgerTarget(
      type = draft.type,
      scope = draft.scope,
      party = draft.party,
      category = draft.category,
      entities = dao.getAllPaymentEntities(),
      customers = dao.getAllCustomers(),
      sales = dao.getAllSales(),
      overrideChoice = linkedTargetChoice,
    )
    val linkJson = postLedgerLink(draft, resolvedTarget)

    dao.upsertLedgerEntry(draft.copy(linkJson = linkJson))
    touchTimestampKey("daily_ledger_timestamp", SarimWebSchema.COL_DAILY_LEDGER, now)
  }

  suspend fun deleteDailyLedgerEntry(id: String) {
    val now = System.currentTimeMillis()
    val l = dao.getAllDailyLedger().firstOrNull { it.id == id }
    if (l != null && SarimLinkEngine.isTabOwnedLedgerRow(l)) {
      throw IllegalStateException(SarimLinkEngine.tabEntryNotice(l))
    }
    val groupId = if (l != null && !l.linkJson.isNullOrBlank()) {
      SarimUuid.generateUUID("${SarimUuid.PREFIX_GRP}_dl", tsMs = now)
    } else null
    if (l != null) {
      checkAndRemoveLedgerLink(l, now, groupId = groupId)
    }
    dao.softDeleteLedgerEntry(id, now)
    recordSingletonDeletion(
      collection = SarimWebSchema.COL_DAILY_LEDGER,
      docId = id,
      title = l?.party?.ifBlank { l.mode } ?: "Ledger Entry",
      subtitle = l?.category ?: l?.type ?: "",
      amountText = "Rs ${l?.amount ?: 0.0}",
      snapshotMap = l?.let { SarimWebSchema.dailyLedgerEntryToMap(it) },
      groupId = groupId,
    )
  }

  /** Reconciles tab-owned Daily Ledger rows against live Sales, Payments, and Calculator Settlements. */
  suspend fun syncLedgerFromTabs(now: Long = System.currentTimeMillis()) {
    val allLedger = dao.getAllDailyLedger()
    val byId = allLedger.associateBy { it.id }
    val activeSales = dao.getAllSales().filter { !it.isDeleted }.associateBy { it.id }
    val activePayments = dao.getAllPaymentTransactions().filter { !it.isDeleted }.associateBy { it.id }
    val activeCalcs = dao.getAllSettlements().filter { !it.isDeleted }.associateBy { it.id }
    var changed = false

    // 1. Prune orphan tab-owned rows whose source record was deleted or toggled addToLedger = false
    allLedger.filter { !it.isDeleted && SarimLinkEngine.isTabOwnedLedgerRow(it) }.forEach { led ->
      val keep = when (led.sourceType.uppercase()) {
        "SALE" -> activeSales[led.sourceId]?.let { s ->
          s.addToLedger && (s.mode == "COLLECTION" || s.mode == "MONEY_OUT" || (s.mode == "SALE" && s.paymentType == "CASH"))
        } == true
        "PAYMENT" -> activePayments[led.sourceId]?.addToLedger == true
        "CALCULATOR" -> activeCalcs[led.sourceId]?.addToLedger == true
        else -> true
      }
      if (!keep) {
        dao.softDeleteLedgerEntry(led.id, now)
        changed = true
      }
    }

    // 2. Ensure Sales with addToLedger=true have an up-to-date ledger row
    activeSales.values.filter { s ->
      !s.isRepSale && s.addToLedger && (
        (s.mode == "SALE" && s.paymentType == "CASH" && s.totalValue > 0.0) ||
          ((s.mode == "COLLECTION" || s.mode == "MONEY_OUT") && (s.amountReceived > 0.0 || s.totalValue > 0.0))
        )
    }.forEach { s ->
      val lid = s.ledgerId?.takeIf { it.isNotBlank() } ?: return@forEach
      val amt = if (s.mode == "SALE") s.totalValue else s.amountReceived.takeIf { it > 0.0 } ?: s.totalValue
      val wantType = if (s.mode == "MONEY_OUT") "OUT" else "IN"
      val cur = byId[lid]
      if (cur == null || cur.isDeleted || kotlin.math.abs(cur.amount - amt) > 0.01 || cur.party != s.customerName || cur.type != wantType) {
        dao.upsertLedgerEntry(
          DailyLedgerEntity(
            id = lid,
            dateEpoch = s.dateEpoch,
            type = wantType,
            amount = amt,
            mode = cur?.mode?.takeIf { it.isNotBlank() } ?: "Cash",
            party = s.customerName,
            note = modeLabelForSale(s),
            category = if (s.mode == "SALE") "Cash Sale" else if (s.mode == "COLLECTION") "Collection" else "Customer Payout",
            sourceType = "SALE",
            sourceId = s.id,
            dateStr = s.dateStr.ifBlank { SarimFormat.dateInput(s.dateEpoch) },
            linkJson = JsonCodecs.encodeLedgerLink(mapOf("saleId" to s.id, "isRep" to s.isRepSale)),
            createdAt = cur?.createdAt ?: s.createdAt,
            updatedAt = now,
          )
        )
        changed = true
      }
    }

    // 3. Ensure Payments with addToLedger=true have an up-to-date ledger row
    activePayments.values.filter { it.addToLedger && it.amount > 0.0 }.forEach { tx ->
      val lid = tx.ledgerId?.takeIf { it.isNotBlank() } ?: return@forEach
      val wantType = if (tx.type == "PAYMENT_IN") "IN" else "OUT"
      val cur = byId[lid]
      val linkedByManual = cur?.let { !SarimLinkEngine.isTabOwnedLedgerRow(it) } == true
      if (!linkedByManual && (cur == null || cur.isDeleted || kotlin.math.abs(cur.amount - tx.amount) > 0.01 || cur.party != tx.entityOrExpenseName || cur.type != wantType)) {
        dao.upsertLedgerEntry(
          DailyLedgerEntity(
            id = lid,
            dateEpoch = tx.dateEpoch,
            type = wantType,
            amount = tx.amount,
            mode = cur?.mode?.takeIf { it.isNotBlank() } ?: "Cash",
            party = tx.entityOrExpenseName,
            note = tx.description,
            category = if (tx.type == "EXPENSE") (tx.category ?: tx.entityOrExpenseName) else tx.type,
            sourceType = "PAYMENT",
            sourceId = tx.id,
            dateStr = tx.dateStr.ifBlank { SarimFormat.dateInput(tx.dateEpoch) },
            linkJson = JsonCodecs.encodeLedgerLink(mapOf("txId" to tx.id, "expenseId" to (tx.expenseId ?: ""))),
            createdAt = cur?.createdAt ?: tx.createdAt,
            updatedAt = now,
          )
        )
        changed = true
      }
    }

    if (changed) {
      touchTimestampKey("daily_ledger_timestamp", SarimWebSchema.COL_DAILY_LEDGER, now)
    }
  }

  private fun modeLabelForSale(s: SaleTransactionEntity): String =
    "${s.mode} · ${s.storeName}".trim(' ', '·')

  private suspend fun revertSettlementLinks(s: CalculatorSettlementEntity, now: Long, groupId: String?) {
    val allSales = dao.getAllSales()
    val linkedSalesIds = JsonCodecs.decodeStrings(s.linkedSalesIdsJson).toSet()
    val linkedRepSalesIds = JsonCodecs.decodeStrings(s.linkedRepSalesIdsJson).toSet()

    // Check if transferSaleId is already settled before reversing
    s.transferSaleId?.takeIf { it.isNotBlank() }?.let { tId ->
      allSales.firstOrNull { it.id == tId && !it.isDeleted }?.let { transferSale ->
        if (transferSale.creditReceived || transferSale.partialPaymentReceived > 0.01) {
          throw IllegalStateException("The transfer to ${transferSale.customerName} is already paid or reconciled.")
        }
        dao.softDeleteSale(tId, now)
        enqueueOutboxDelete(
          collection = SarimWebSchema.COL_SALES,
          docId = tId,
          title = "Rep Transfer: ${s.repName} → ${transferSale.customerName}",
          subtitle = "${transferSale.quantityKg} kg",
          amountText = "Rs ${transferSale.totalValue}",
          snapshotMap = SarimWebSchema.saleToMap(transferSale),
          groupId = groupId,
        )
      }
    }

    // Revert Admin sales reconciled by this settlement (sets creditReceived = false and clears usedInCalcId!)
    allSales.filter { !it.isDeleted && (!it.isRepSale && (it.id in linkedSalesIds || it.usedInCalcId == s.id)) }.forEach { sale ->
      val reverted = sale.copy(creditReceived = false, usedInCalcId = null, syncPending = true, updatedAt = now)
      dao.upsertSale(reverted)
      enqueueOutboxSet(SarimWebSchema.COL_SALES, reverted.id, SarimWebSchema.saleToMap(reverted))
    }

    // Revert Rep sub-sales (`rep`) linked by this settlement
    allSales.filter { !it.isDeleted && it.isRepSale && (it.id in linkedRepSalesIds || it.usedInCalcId == s.id) }.forEach { repSale ->
      val reverted = repSale.copy(usedInCalcId = null, syncPending = true, updatedAt = now)
      dao.upsertSale(reverted)
      enqueueOutboxSet(SarimWebSchema.COL_REP, reverted.id, SarimWebSchema.saleToMap(reverted))
    }

    // Reverse linked return log & return production record
    s.returnLogId?.takeIf { it.isNotBlank() }?.let { rLogId ->
      val log = dao.getAllReturns().firstOrNull { it.id == rLogId }
      dao.softDeleteReturnLog(rLogId, now)
      if (groupId != null && log != null) {
        enqueueOutboxDelete(
          collection = SarimWebSchema.COL_RETURNS,
          docId = rLogId,
          title = "Return: ${log.seller}",
          subtitle = "${s.returnedKg} kg",
          amountText = "${s.returnedKg} kg",
          snapshotMap = SarimWebSchema.returnLogToMap(log),
          groupId = groupId,
        )
      }
    }
    s.returnEntryId?.takeIf { it.isNotBlank() }?.let { rEntryId ->
      val prodRet = dao.getAllProductionEntries().firstOrNull { it.id == rEntryId }
      dao.softDeleteProductionEntry(rEntryId, now)
      if (groupId != null && prodRet != null) {
        enqueueOutboxDelete(
          collection = SarimWebSchema.COL_PRODUCTION,
          docId = rEntryId,
          title = "Return: ${s.repName}",
          subtitle = "${s.returnedKg} kg",
          amountText = "${s.returnedKg} kg",
          snapshotMap = SarimWebSchema.productionToMap(prodRet),
          groupId = groupId,
        )
      }
    }

    // Reverse CHORA if expired was applied
    if (s.expiredKg > 0.0 && s.expiredApplied) {
      dao.getAllRawMaterials().firstOrNull { it.name.equals("CHORA", ignoreCase = true) && !it.isDeleted }?.let { chora ->
        val updatedChora = chora.copy(
          stockKg = (chora.stockKg - s.expiredKg).coerceAtLeast(0.0),
          collectedKg = (chora.collectedKg - s.expiredKg).coerceAtLeast(0.0),
          syncPending = true,
          updatedAt = now,
        )
        dao.upsertRawMaterial(updatedChora)
        enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updatedChora.id, SarimWebSchema.materialToMap(updatedChora))
      }
    }

    // Soft-delete prior ledger rows created by this settlement
    var touchedLedger = false
    dao.getAllDailyLedger().filter { !it.isDeleted && it.sourceType == "CALCULATOR" && it.sourceId == s.id }.forEach { l ->
      dao.softDeleteLedgerEntry(l.id, now)
      touchedLedger = true
    }
    if (touchedLedger) touchTimestampKey("daily_ledger_timestamp", SarimWebSchema.COL_DAILY_LEDGER, now)
  }

  suspend fun saveCalculatorSettlement(settlement: CalculatorSettlementEntity) {
    val now = System.currentTimeMillis()
    val existing = dao.getAllSettlements().firstOrNull { it.id == settlement.id && !it.isDeleted }
    if (existing != null) {
      revertSettlementLinks(existing, now, groupId = null)
    }

    val dateStr = settlement.dateStr.ifBlank { SarimFormat.dateInput(settlement.dateEpoch) }
    val statusClass = when {
      settlement.status.contains("SHORT", ignoreCase = true) -> "status-short"
      settlement.status.contains("OVER", ignoreCase = true) -> "status-over"
      else -> "status-ok"
    }

    JsonCodecs.decodeLineItems(settlement.expenseItemsJson).forEach {
      appendCategoryToKv("field_expense_categories", it.category, now)
    }
    JsonCodecs.decodeLineItems(settlement.commissionItemsJson).forEach {
      appendCategoryToKv("commission_categories", it.category, now)
    }

    var returnLogId: String? = null
    var returnEntryId: String? = null
    var transferSaleId: String? = null
    var effectiveReturnType: String? = null
    var effectiveReturnRep: String? = null
    val isTransfer = settlement.returnedKg > 0.0 &&
      settlement.returnType.equals("TRANSFER", ignoreCase = true) &&
      !settlement.returnRep.isNullOrBlank()

    if (isTransfer) {
      val targetRep = settlement.returnRep!!.trim()
      val targetContact = dao.getAllCustomers().firstOrNull {
        !it.isDeleted && !it.isRepCustomer && it.name.equals(targetRep, ignoreCase = true)
      }
      val fallbackStorePrice = dao.getAllStores().firstOrNull { it.storeKey == settlement.returnStoreKey }?.salePricePerKg
        ?: dao.getAllStores().firstOrNull()?.salePricePerKg
        ?: 400.0
      val transferUnitPrice = targetContact?.customSalePrice?.takeIf { it > 0.0 } ?: fallbackStorePrice
      val transferTotal = SarimLinkEngine.round2(settlement.returnedKg * transferUnitPrice)
      val tId = SarimUuid.generateUUID(SarimUuid.PREFIX_SALE, tsMs = now)
      val transferSale = SaleTransactionEntity(
        id = tId,
        dateEpoch = settlement.dateEpoch,
        mode = "SALE",
        customerId = "",
        customerName = targetRep,
        repName = targetRep,
        storeKey = "N/A",
        storeName = "TRANSFER",
        quantityKg = SarimLinkEngine.round2(settlement.returnedKg),
        pricePerKg = transferUnitPrice,
        totalValue = transferTotal,
        totalCost = transferTotal,
        profit = 0.0,
        paymentType = "CREDIT",
        amountReceived = 0.0,
        dateStr = dateStr,
        supplyDate = dateStr,
        timeStr = SarimFormat.formatTime(now),
        creditReceived = false,
        isRepSale = false,
        affectsInventory = false,
        isRepTransfer = true,
        repTransferFrom = settlement.repName,
        notes = "Transferred from ${settlement.repName}",
        transactionType = "SALE",
        syncPending = true,
        createdAt = now,
        updatedAt = now,
      )
      dao.upsertSale(transferSale)
      enqueueOutboxSet(SarimWebSchema.COL_SALES, transferSale.id, SarimWebSchema.saleToMap(transferSale))
      transferSaleId = transferSale.id
      effectiveReturnType = "TRANSFER"
      effectiveReturnRep = targetRep
    } else if (settlement.returnedKg > 0.0 && settlement.returnStoreKey.isNotBlank()) {
      val log = ReturnLogEntity(
        id = SarimUuid.generateUUID(SarimUuid.PREFIX_RETLOG, tsMs = now),
        date = dateStr,
        time = SarimFormat.formatTime(now),
        store = settlement.returnStoreKey,
        quantity = settlement.returnedKg,
        seller = settlement.repName,
        dateEpoch = settlement.dateEpoch,
        updatedAt = now,
      )
      returnLogId = log.id
      dao.upsertReturnLog(log)
      enqueueOutboxSet(SarimWebSchema.COL_RETURNS, log.id, SarimWebSchema.returnLogToMap(log))

      val retStore = dao.getAllStores().firstOrNull { it.storeKey == settlement.returnStoreKey }
      val prodReturn = ProductionEntryEntity(
        id = SarimUuid.generateUUID(SarimUuid.PREFIX_RET, tsMs = now),
        dateEpoch = settlement.dateEpoch,
        storeKey = settlement.returnStoreKey,
        storeName = retStore?.name ?: settlement.returnStoreKey.uppercase(),
        formulaId = retStore?.formulaId ?: "",
        formulaName = "Return",
        grossWeightKg = settlement.returnedKg,
        containerWeightKg = 0.0,
        netWeightKg = settlement.returnedKg,
        formulaUnitsUsed = 0.0,
        formulaUnitCost = 0.0,
        totalFormulaCost = 0.0,
        costPerKg = if (settlement.netSoldKg > 0.0001) settlement.totalCost / settlement.netSoldKg else 0.0,
        salePricePerKg = retStore?.salePricePerKg ?: 0.0,
        profitPerKg = 0.0,
        totalValue = 0.0,
        totalProfit = 0.0,
        dateStr = dateStr,
        timeStr = SarimFormat.formatTime(now),
        isReturn = true,
        returnedBy = settlement.repName,
        returnNote = "Rep settlement return",
        returnLogId = returnLogId,
        syncPending = true,
        updatedAt = now,
      )
      returnEntryId = prodReturn.id
      dao.upsertProductionEntry(prodReturn)
      enqueueOutboxSet(SarimWebSchema.COL_PRODUCTION, prodReturn.id, SarimWebSchema.productionToMap(prodReturn))
      effectiveReturnType = "RETURN"
    }

    // Reconcile pending Admin deliveries (customerName == repName, paymentType == CREDIT, !creditReceived) + Rep sub-sales + Rep returns
    val allSales = dao.getAllSales()
    val allSettlements = dao.getAllSettlements()
    val cycle = SarimLinkEngine.getCalcCycleSelection(
      repName = settlement.repName,
      sales = allSales,
      settlements = allSettlements,
      manualFromEpoch = settlement.fromDateEpoch,
      toEpoch = settlement.toDateEpoch,
      excludeCalcId = settlement.id,
    )

    val linkedSalesIds = mutableListOf<String>()
    cycle.selectedDeliveries.forEach { delivery ->
      val reconciled = delivery.copy(
        creditReceived = true,
        usedInCalcId = settlement.id,
        syncPending = true,
        updatedAt = now,
      )
      dao.upsertSale(reconciled)
      enqueueOutboxSet(SarimWebSchema.COL_SALES, reconciled.id, SarimWebSchema.saleToMap(reconciled))
      linkedSalesIds += reconciled.id
    }

    val linkedRepSalesIds = mutableListOf<String>()
    cycle.pendingRepSales.forEach { repSale ->
      val reconciled = repSale.copy(
        usedInCalcId = settlement.id,
        syncPending = true,
        updatedAt = now,
      )
      dao.upsertSale(reconciled)
      enqueueOutboxSet(SarimWebSchema.COL_REP, reconciled.id, SarimWebSchema.saleToMap(reconciled))
      linkedRepSalesIds += reconciled.id
    }

    val coveredReturnIds = SarimLinkEngine.pickReturnsCovered(
      pending = cycle.pendingReturns,
      retTarget = settlement.returnedKg,
      expTarget = settlement.expiredKg,
    ).map { it.id }

    val updatedSettlement = settlement.copy(
      dateStr = dateStr,
      returnStoreKey = if (isTransfer) "" else if (settlement.returnedKg > 0.0) settlement.returnStoreKey else "",
      returnLogId = returnLogId,
      returnEntryId = returnEntryId,
      transferSaleId = transferSaleId,
      returnRep = effectiveReturnRep,
      returnType = effectiveReturnType,
      statusClass = statusClass,
      expiredApplied = settlement.expiredKg > 0.0,
      linkedSalesIdsJson = JsonCodecs.encodeStrings(linkedSalesIds),
      linkedRepSalesIdsJson = JsonCodecs.encodeStrings(linkedRepSalesIds),
      linkedRepReturnIdsJson = JsonCodecs.encodeStrings(coveredReturnIds),
      syncPending = true,
      updatedAt = now,
    )
    dao.upsertSettlement(updatedSettlement)
    enqueueOutboxSet(SarimWebSchema.COL_CALCULATOR, updatedSettlement.id, SarimWebSchema.settlementToMap(updatedSettlement))

    if (settlement.expiredKg > 0.0) {
      val chora = dao.getAllRawMaterials().firstOrNull { it.name.equals("CHORA", ignoreCase = true) && !it.isDeleted }
      if (chora != null) {
        val updatedChora = chora.copy(
          stockKg = chora.stockKg + settlement.expiredKg,
          collectedKg = chora.collectedKg + settlement.expiredKg,
          syncPending = true,
          updatedAt = now,
        )
        dao.upsertRawMaterial(updatedChora)
        enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updatedChora.id, SarimWebSchema.materialToMap(updatedChora))
      }
    }

    if (settlement.addToLedger) {
      val calcLink = JsonCodecs.encodeLedgerLink(mapOf("calcId" to settlement.id))
      if (settlement.receivedCash > 0.0) {
        dao.upsertLedgerEntry(
          DailyLedgerEntity(
            dateEpoch = settlement.dateEpoch,
            type = "IN",
            amount = settlement.receivedCash,
            mode = "Cash",
            party = settlement.repName,
            note = "Rep Settlement Cash",
            category = "Calculator",
            sourceType = "CALCULATOR",
            sourceId = settlement.id,
            dateStr = dateStr,
            linkJson = calcLink,
            updatedAt = now,
          )
        )
      }
      if (settlement.fieldExpenses > 0.0) {
        dao.upsertLedgerEntry(
          DailyLedgerEntity(
            dateEpoch = settlement.dateEpoch,
            type = "OUT",
            amount = settlement.fieldExpenses,
            mode = "Cash",
            party = settlement.repName,
            note = "Rep Field Expenses",
            category = "Field Expenses",
            sourceType = "CALCULATOR",
            sourceId = settlement.id,
            dateStr = dateStr,
            linkJson = calcLink,
            updatedAt = now,
          )
        )
      }
      if (settlement.commissionPaid > 0.0) {
        dao.upsertLedgerEntry(
          DailyLedgerEntity(
            dateEpoch = settlement.dateEpoch,
            type = "OUT",
            amount = settlement.commissionPaid,
            mode = "Cash",
            party = settlement.repName,
            note = "Rep Commission Paid",
            category = "Commission",
            sourceType = "CALCULATOR",
            sourceId = settlement.id,
            dateStr = dateStr,
            linkJson = calcLink,
            updatedAt = now,
          )
        )
      }
      touchTimestampKey("daily_ledger_timestamp", SarimWebSchema.COL_DAILY_LEDGER, now)
    }
  }

  suspend fun deleteSettlement(id: String) {
    val now = System.currentTimeMillis()
    val s = dao.getAllSettlements().firstOrNull { it.id == id } ?: return
    val groupId = SarimUuid.generateUUID("${SarimUuid.PREFIX_GRP}_calc", tsMs = now)

    revertSettlementLinks(s, now, groupId = groupId)

    dao.softDeleteSettlement(id, now)
    enqueueOutboxDelete(
      collection = SarimWebSchema.COL_CALCULATOR,
      docId = id,
      title = "Settlement: ${s.repName}",
      subtitle = "${s.status} · ${s.netSoldKg} kg",
      amountText = "Rs ${s.receivedCash}",
      snapshotMap = SarimWebSchema.settlementToMap(s),
      groupId = groupId,
    )
  }

  suspend fun saveStockTransfer(from: StoreEntity, to: StoreEntity, dateEpoch: Long, qtyKg: Double, note: String) {
    val now = System.currentTimeMillis()
    val transfer = StockTransferEntity(
      id = SarimUuid.generateUUID(SarimUuid.PREFIX_TRFPAIR, tsMs = now),
      dateEpoch = dateEpoch,
      fromStoreKey = from.storeKey,
      fromStoreName = from.name,
      toStoreKey = to.storeKey,
      toStoreName = to.name,
      quantityKg = qtyKg,
      note = note.trim(),
      dateStr = SarimFormat.dateInput(dateEpoch),
      outDocId = SarimUuid.generateUUID(SarimUuid.PREFIX_TRF, tsMs = now),
      inDocId = SarimUuid.generateUUID(SarimUuid.PREFIX_TRF, tsMs = now),
      createdAt = now,
      updatedAt = now,
    )
    dao.upsertStockTransfer(transfer)
    val (outPair, inPair) = SarimWebSchema.stockTransferToOutInMaps(transfer)
    enqueueOutboxSet(SarimWebSchema.COL_PRODUCTION, outPair.first, outPair.second)
    enqueueOutboxSet(SarimWebSchema.COL_PRODUCTION, inPair.first, inPair.second)
  }

  suspend fun deleteStockTransfer(id: String) {
    val now = System.currentTimeMillis()
    val tr = dao.getAllStockTransfers().firstOrNull { it.id == id } ?: return
    SarimLinkEngine.getStockTransferDeleteBlockReason(
      transfer = tr,
      production = dao.getAllProductionEntries(),
      sales = dao.getAllSales(),
      stockTransfers = dao.getAllStockTransfers(),
      settlements = dao.getAllSettlements(),
    )?.let { msg -> throw IllegalStateException(msg) }
    val groupId = SarimUuid.generateUUID("${SarimUuid.PREFIX_GRP}_st", tsMs = now)
    val (outPair, inPair) = SarimWebSchema.stockTransferToOutInMaps(tr)
    dao.softDeleteStockTransfer(id, now)
    enqueueOutboxDelete(
      collection = SarimWebSchema.COL_PRODUCTION,
      docId = outPair.first,
      title = "Stock Out: ${tr.fromStoreName} -> ${tr.toStoreName}",
      subtitle = "${tr.quantityKg} kg",
      amountText = "${tr.quantityKg} kg",
      snapshotMap = outPair.second,
      groupId = groupId,
      recordType = "stock_transfer",
    )
    enqueueOutboxDelete(
      collection = SarimWebSchema.COL_PRODUCTION,
      docId = inPair.first,
      title = "Stock In: ${tr.fromStoreName} -> ${tr.toStoreName}",
      subtitle = "${tr.quantityKg} kg",
      amountText = "${tr.quantityKg} kg",
      snapshotMap = inPair.second,
      groupId = groupId,
      recordType = "stock_transfer",
    )
  }

  suspend fun savePaymentTransfer(from: PaymentEntityRecord, to: PaymentEntityRecord, dateEpoch: Long, amount: Double, note: String, photoUri: String) {
    val now = System.currentTimeMillis()
    val updatedFrom = from.copy(currentBalance = from.currentBalance - amount, syncPending = true, updatedAt = now)
    val updatedTo = to.copy(currentBalance = to.currentBalance + amount, syncPending = true, updatedAt = now)
    dao.upsertPaymentEntity(updatedFrom)
    dao.upsertPaymentEntity(updatedTo)
    enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, updatedFrom.id, SarimWebSchema.entityToMap(updatedFrom))
    enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, updatedTo.id, SarimWebSchema.entityToMap(updatedTo))

    val transfer = PaymentTransferEntity(
      id = SarimUuid.generateUUID(SarimUuid.PREFIX_TRFPAIR, tsMs = now),
      dateEpoch = dateEpoch,
      fromEntityId = from.id,
      fromEntityName = from.name,
      toEntityId = to.id,
      toEntityName = to.name,
      amount = amount,
      note = note.trim(),
      photoUri = photoUri,
      dateStr = SarimFormat.dateInput(dateEpoch),
      outDocId = SarimUuid.generateUUID(SarimUuid.PREFIX_PAY, tsMs = now),
      inDocId = SarimUuid.generateUUID(SarimUuid.PREFIX_PAY, tsMs = now),
      createdAt = now,
      updatedAt = now,
    )
    dao.upsertPaymentTransfer(transfer)
    val (outPair, inPair) = SarimWebSchema.paymentTransferToOutInMaps(transfer)
    enqueueOutboxSet(SarimWebSchema.COL_TRANSACTIONS, outPair.first, outPair.second)
    enqueueOutboxSet(SarimWebSchema.COL_TRANSACTIONS, inPair.first, inPair.second)

    if (photoUri.isNotBlank()) {
      upsertPhotoKey("expense:${transfer.outDocId}", photoUri, now)
      upsertPhotoKey("expense:${transfer.inDocId}", photoUri, now)
    }
  }

  suspend fun deletePaymentTransfer(id: String, groupIdOverride: String? = null) {
    val now = System.currentTimeMillis()
    val tr = dao.getAllPaymentTransfers().firstOrNull { it.id == id } ?: return
    val groupId = groupIdOverride ?: SarimUuid.generateUUID("${SarimUuid.PREFIX_GRP}_pt", tsMs = now)

    dao.getAllPaymentEntities().firstOrNull { it.id == tr.fromEntityId && !it.isDeleted }?.let { fromEnt ->
      val restored = fromEnt.copy(currentBalance = fromEnt.currentBalance + tr.amount, syncPending = true, updatedAt = now)
      dao.upsertPaymentEntity(restored)
      enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, restored.id, SarimWebSchema.entityToMap(restored))
    }
    dao.getAllPaymentEntities().firstOrNull { it.id == tr.toEntityId && !it.isDeleted }?.let { toEnt ->
      val restored = toEnt.copy(currentBalance = toEnt.currentBalance - tr.amount, syncPending = true, updatedAt = now)
      dao.upsertPaymentEntity(restored)
      enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, restored.id, SarimWebSchema.entityToMap(restored))
    }

    if (tr.photoUri.isNotBlank()) {
      markPhotoDeleted("expense:${tr.outDocId}", now)
      markPhotoDeleted("expense:${tr.inDocId}", now)
    }

    val (outPair, inPair) = SarimWebSchema.paymentTransferToOutInMaps(tr)
    dao.softDeletePaymentTransfer(id, now)
    enqueueOutboxDelete(
      collection = SarimWebSchema.COL_TRANSACTIONS,
      docId = outPair.first,
      title = "Transfer OUT: ${tr.fromEntityName} -> ${tr.toEntityName}",
      subtitle = tr.note,
      amountText = "Rs ${tr.amount}",
      snapshotMap = outPair.second,
      groupId = groupId,
      recordType = "payment_transfer",
    )
    enqueueOutboxDelete(
      collection = SarimWebSchema.COL_TRANSACTIONS,
      docId = inPair.first,
      title = "Transfer IN: ${tr.fromEntityName} -> ${tr.toEntityName}",
      subtitle = tr.note,
      amountText = "Rs ${tr.amount}",
      snapshotMap = inPair.second,
      groupId = groupId,
      recordType = "payment_transfer",
    )
  }

  suspend fun saveMixCalc(entity: SavedMixCalcEntity) {
    val now = System.currentTimeMillis()
    dao.upsertMixCalc(entity.copy(updatedAt = now, syncPending = true))
    touchTimestampKey("formulas_timestamp", SarimWebSchema.COL_FORMULAS, now)
  }

  suspend fun deleteMixCalc(id: String) {
    val mc = dao.getAllMixCalcs().firstOrNull { it.id == id }
    dao.hardDeleteMixCalc(id)
    val snapshot = mc?.let {
      linkedMapOf<String, Any?>(
        "id" to it.id,
        "kind" to it.kind,
        "name" to it.name,
        "formulaId" to it.formulaId,
        "unit" to it.unit,
        "mode" to it.mode,
        "inputValue" to it.inputValue,
      )
    }
    recordSingletonDeletion(
      collection = SarimWebSchema.COL_FORMULAS,
      docId = id,
      title = "Mix Calc: ${mc?.name ?: id}",
      subtitle = mc?.formulaName ?: "",
      amountText = mc?.summaryText ?: "",
      snapshotMap = snapshot,
      recordType = "saved_mix_calcs",
    )
  }

  suspend fun upsertApproval(req: ApprovalRequestEntity) = dao.upsertApproval(req)

  suspend fun resolveApproval(req: ApprovalRequestEntity, approved: Boolean) {
    val status = if (approved) "approved" else "discarded"
    val updated = req.copy(status = if (approved) "APPROVED" else "DISCARDED", syncPending = true)
    dao.upsertApproval(updated)
    enqueueOutboxSet(
      SarimWebSchema.COL_PENDING,
      req.id,
      mapOf(
        "id" to req.id,
        "status" to status,
        "resolvedAt" to System.currentTimeMillis(),
      ),
    )
  }

  suspend fun getSalesReps(): List<String> {
    val raw = safeReader.getKvByUserKeyOnce("reps")?.value ?: "[]"
    return JsonCodecs.decodeStrings(raw)
      .map { it.trim() }
      .filter { it.isNotBlank() }
      .distinctBy { it.uppercase() }
  }

  suspend fun saveSalesReps(reps: List<String>) {
    val now = System.currentTimeMillis()
    val clean = reps.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.uppercase() }
    dao.upsertKv(
      KvStoreEntity(
        fullKey = "reps",
        userKey = "reps",
        collection = SarimWebSchema.COL_SETTINGS,
        rowType = "settings",
        value = JsonCodecs.encodeStrings(clean),
        ts = now,
        createdAt = now,
      )
    )
    touchTimestampKey("reps_timestamp", SarimWebSchema.COL_SETTINGS, now)
    dao.upsertKv(
      KvStoreEntity(
        fullKey = "reps_dirty",
        userKey = "reps_dirty",
        collection = SarimWebSchema.COL_SETTINGS,
        rowType = "meta",
        value = "1",
        ts = now,
        createdAt = now,
      )
    )
  }

  suspend fun ensureRepInList(name: String) {
    val clean = name.trim()
    if (clean.isBlank()) return
    val current = getSalesReps()
    if (current.none { it.equals(clean, ignoreCase = true) }) {
      saveSalesReps(current + clean)
    }
  }

  suspend fun dropRepFromList(name: String) {
    val clean = name.trim()
    if (clean.isBlank()) return
    val current = getSalesReps()
    val next = current.filter { !it.equals(clean, ignoreCase = true) }
    if (next.size != current.size) {
      saveSalesReps(next)
    }
  }

  suspend fun upsertLocalAppAccount(acct: AppAccountEntity) {
    dao.upsertAccount(acct)
  }

  suspend fun deleteLocalAppAccount(id: String) {
    dao.deleteAccount(id)
  }

  suspend fun addAppAccount(email: String, name: String, rolesCsv: String) {
    val acct = AppAccountEntity(email = email.trim(), name = name.trim(), rolesCsv = rolesCsv)
    dao.upsertAccount(acct)
    touchTimestampKey("team_timestamp", "settings")
  }

  suspend fun deleteAppAccount(id: String) {
    dao.deleteAccount(id)
    touchTimestampKey("team_timestamp", "settings")
  }

  suspend fun readYearCloseMeta(): Triple<Int, Long?, String?> {
    val raw = safeReader.getKvByUserKeyOnce("settings")?.value ?: "{}"
    val obj = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    val count = obj.optInt("fyCloseCount", 0)
    val lastAt = if (obj.has("lastYearClosedAt") && !obj.isNull("lastYearClosedAt")) obj.optLong("lastYearClosedAt") else null
    val lastDate = obj.optString("lastYearClosedDate", "").takeIf { it.isNotBlank() }
    return Triple(count, lastAt, lastDate)
  }

  /**
   * Year-close bookkeeping that is not about transactions (the transactions themselves are merged by
   * `SarimBackupService.closeFinancialYear`): carry customer debt and entity balances forward and
   * record the close in settings.
   */
  suspend fun rollBalancesForwardAndMarkYearClosed() {
    val now = System.currentTimeMillis()
    val customersList = dao.getAllCustomers().filter { !it.isDeleted }
    customersList.forEach { c ->
      val updated = c.copy(oldDebit = c.currentCredit, totalQuantityKg = 0.0, totalSaleValue = 0.0, totalReceived = 0.0, syncPending = true, updatedAt = now)
      dao.upsertCustomer(updated)
      val col = if (updated.isRepCustomer) SarimWebSchema.COL_CLIENTS else SarimWebSchema.COL_CUSTOMERS
      enqueueOutboxSet(col, updated.id, SarimWebSchema.customerToMap(updated))
    }
    val entitiesList = dao.getAllPaymentEntities().filter { !it.isDeleted }
    entitiesList.forEach { e ->
      val type = if (e.currentBalance >= 0) "CREDIT" else "DEBIT"
      val updated = e.copy(openingType = type, openingAmount = kotlin.math.abs(e.currentBalance), syncPending = true, updatedAt = now)
      dao.upsertPaymentEntity(updated)
      enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, updated.id, SarimWebSchema.entityToMap(updated))
    }
    touchTimestampKey("daily_ledger_timestamp", SarimWebSchema.COL_DAILY_LEDGER, now)

    val (prevCount, _, _) = readYearCloseMeta()
    val nextCount = prevCount + 1
    val closedDateStr = SarimFormat.dateInput(now)
    val settingsObj = readSettingsObject().apply {
      put("fyCloseCount", nextCount)
      put("lastYearClosedAt", now)
      put("lastYearClosedDate", closedDateStr)
    }
    writeSettingsObject(settingsObj, now)
  }

  suspend fun readSettingsObject(): JSONObject {
    val raw = safeReader.getKvByUserKeyOnce("settings")?.value ?: "{}"
    return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
  }

  /** Web `_doRestoreMerge`: a backup's `settings` replace the local ones when they differ. */
  suspend fun applyBackupSettings(incoming: JSONObject): Boolean {
    if (incoming.length() == 0) return false
    if (readSettingsObject().toString() == incoming.toString()) return false
    writeSettingsObject(incoming, System.currentTimeMillis())
    return true
  }

  /** Reverse of a year close (web `_doYearCloseRestore`): put the counters back to the backup's snapshot. */
  suspend fun restoreYearCloseMeta(count: Int, lastAt: Long?, lastDate: String?) {
    val now = System.currentTimeMillis()
    val obj = readSettingsObject().apply {
      put("fyCloseCount", count)
      put("lastYearClosedAt", lastAt ?: JSONObject.NULL)
      put("lastYearClosedDate", lastDate ?: JSONObject.NULL)
    }
    writeSettingsObject(obj, now)
  }

  private suspend fun writeSettingsObject(settingsObj: JSONObject, now: Long) {
    dao.upsertKv(
      KvStoreEntity(
        fullKey = "settings",
        userKey = "settings",
        collection = SarimWebSchema.COL_SETTINGS,
        rowType = "settings",
        value = settingsObj.toString(),
        ts = now,
        createdAt = now,
      )
    )
    touchTimestampKey("settings_timestamp", SarimWebSchema.COL_SETTINGS, now)
    dao.upsertKv(
      KvStoreEntity(
        fullKey = "settings_dirty",
        userKey = "settings_dirty",
        collection = SarimWebSchema.COL_SETTINGS,
        rowType = "meta",
        value = "1",
        ts = now,
        createdAt = now,
      )
    )
  }

  suspend fun repairSaleLinks(): Int {
    val now = System.currentTimeMillis()
    val allSales = dao.getAllSales()
    val plan = SarimLinkEngine.planLinkRepairs(allSales, now)
    if (plan.updatedSales.isEmpty()) return 0

    val touchedCustomers = linkedSetOf<Pair<String, Boolean>>()
    plan.updatedSales.forEach { s ->
      dao.upsertSale(s)
      val col = if (s.isRepSale) SarimWebSchema.COL_REP else SarimWebSchema.COL_SALES
      enqueueOutboxSet(col, s.id, SarimWebSchema.saleToMap(s))
      if (s.customerName.isNotBlank()) touchedCustomers += (s.customerName.lowercase() to s.isRepSale)
    }

    val updatedSalesAll = dao.getAllSales()
    val settlements = dao.getAllSettlements()
    dao.getAllCustomers().filter { !it.isDeleted }.forEach { c ->
      if ((c.name.lowercase() to c.isRepCustomer) in touchedCustomers) {
        val recomputedCredit = SarimLinkEngine.partyOutstanding(
          name = c.name,
          sales = updatedSalesAll,
          isRepProfile = c.isRepCustomer,
          settlements = settlements,
        )
        if (kotlin.math.abs(c.currentCredit - recomputedCredit) > 0.01) {
          val updatedCust = c.copy(currentCredit = recomputedCredit, syncPending = true, updatedAt = now)
          dao.upsertCustomer(updatedCust)
          val custCol = if (updatedCust.isRepCustomer) SarimWebSchema.COL_CLIENTS else SarimWebSchema.COL_CUSTOMERS
          enqueueOutboxSet(custCol, updatedCust.id, SarimWebSchema.customerToMap(updatedCust))
        }
      }
    }
    return plan.updatedSales.size
  }

  suspend fun deleteCustomerEditHistoryEntry(customerId: String, index: Int): Boolean {
    val now = System.currentTimeMillis()
    val existing = dao.getAllCustomers().firstOrNull { it.id == customerId && !it.isDeleted } ?: return false
    val history = JsonCodecs.decodeEditHistory(existing.editHistoryJson).toMutableList()
    if (index !in history.indices) return false
    history.removeAt(index)
    val updated = existing.copy(
      editHistoryJson = JsonCodecs.encodeEditHistory(history),
      syncPending = true,
      updatedAt = now,
    )
    dao.upsertCustomer(updated)
    val col = if (updated.isRepCustomer) SarimWebSchema.COL_CLIENTS else SarimWebSchema.COL_CUSTOMERS
    enqueueOutboxSet(col, updated.id, SarimWebSchema.customerToMap(updated))
    return true
  }

  suspend fun deletePaymentEntityEditHistoryEntry(entityId: String, index: Int): Boolean {
    val now = System.currentTimeMillis()
    val existing = dao.getAllPaymentEntities().firstOrNull { it.id == entityId && !it.isDeleted } ?: return false
    val history = JsonCodecs.decodeEditHistory(existing.editHistoryJson).toMutableList()
    if (index !in history.indices) return false
    history.removeAt(index)
    val updated = existing.copy(
      editHistoryJson = JsonCodecs.encodeEditHistory(history),
      syncPending = true,
      updatedAt = now,
    )
    dao.upsertPaymentEntity(updated)
    enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, updated.id, SarimWebSchema.entityToMap(updated))
    return true
  }

  suspend fun patchSaleGpsAndConfirmCustomer(saleId: String, lat: Double, lng: Double): String? {
    val now = System.currentTimeMillis()
    val sale = dao.getAllSales().firstOrNull { it.id == saleId && !it.isDeleted } ?: return null
    val patchedSale = if (sale.latitude == null || sale.longitude == null) {
      val s = sale.copy(latitude = lat, longitude = lng, syncPending = true, updatedAt = now)
      dao.upsertSale(s)
      val saleCol = if (s.isRepSale) SarimWebSchema.COL_REP else SarimWebSchema.COL_SALES
      enqueueOutboxSet(saleCol, s.id, SarimWebSchema.saleToMap(s))
      s
    } else sale

    if (!patchedSale.isRepSale) return null
    val allCusts = dao.getAllCustomers().filter { !it.isDeleted }
    val customer = allCusts.firstOrNull {
      (patchedSale.customerId.isNotBlank() && it.id == patchedSale.customerId) ||
        (it.isRepCustomer && it.name.equals(patchedSale.customerName, ignoreCase = true))
    } ?: return null

    val cluster = SarimLinkEngine.evaluateAutoLocationCluster(
      customer = customer,
      allSales = dao.getAllSales(),
      currentLat = lat,
      currentLng = lng,
      now = now,
    ) ?: return null

    val gpsAddress = String.format(java.util.Locale.US, "GPS: %.6f, %.6f", cluster.first, cluster.second)
    val updatedCust = customer.copy(
      address = gpsAddress,
      locationConfirmed = true,
      syncPending = true,
      updatedAt = now,
    )
    dao.upsertCustomer(updatedCust)
    enqueueOutboxSet(SarimWebSchema.COL_CLIENTS, updatedCust.id, SarimWebSchema.customerToMap(updatedCust))
    return "Location confirmed for ${updatedCust.name} after 3 consistent visits."
  }

  suspend fun applyAuditFix(recordType: String, recordId: String, valNum: Double): Boolean =
    applyAuditFix(recordType = recordType, recordId = recordId, fieldKey = "", newValue = valNum.toString())

  suspend fun applyAuditFix(
    recordType: String,
    recordId: String,
    fieldKey: String,
    newValue: String,
  ): Boolean {
    val now = System.currentTimeMillis()
    val numVal = SarimFormat.parseSafeDouble(newValue)
    return when (recordType) {
      "LINK_REPAIR" -> repairSaleLinks() > 0
      "SALE" -> {
        val sale = dao.getAllSales().firstOrNull { it.id == recordId && !it.isDeleted } ?: return false
        val col = if (sale.isRepSale) SarimWebSchema.COL_REP else SarimWebSchema.COL_SALES
        if (fieldKey == "newId") {
          val newId = SarimUuid.generateUUID(SarimUuid.PREFIX_SALE, tsMs = now)
          val cloned = sale.copy(id = newId, syncPending = true, updatedAt = now)
          dao.upsertSale(cloned)
          enqueueOutboxSet(col, cloned.id, SarimWebSchema.saleToMap(cloned))
          dao.softDeleteSale(sale.id, now)
          enqueueOutboxDelete(
            collection = col,
            docId = sale.id,
            title = "Replaced Duplicate ID: ${sale.customerName}",
            subtitle = sale.id,
            amountText = "Rs ${sale.totalValue}",
            snapshotMap = SarimWebSchema.saleToMap(sale),
          )
          return true
        }
        val updated = when (fieldKey) {
          "dateEpoch" -> {
            val epoch = newValue.toLongOrNull() ?: return false
            val ds = SarimFormat.dateInput(epoch)
            sale.copy(dateEpoch = epoch, dateStr = ds, supplyDate = ds, syncPending = true, updatedAt = now)
          }
          "creditReceived" -> {
            val flag = newValue.equals("true", ignoreCase = true) || newValue == "1"
            sale.copy(creditReceived = flag, syncPending = true, updatedAt = now)
          }
          "quantityKg" -> {
            val q = numVal ?: return false
            val newTotal = SarimLinkEngine.round2(q * sale.pricePerKg)
            val newCost = SarimLinkEngine.round2(if (sale.quantityKg > 0.0001) (sale.totalCost / sale.quantityKg) * q else sale.totalCost)
            sale.copy(
              quantityKg = q,
              totalValue = newTotal,
              totalCost = newCost,
              profit = SarimLinkEngine.round2(newTotal - newCost),
              syncPending = true,
              updatedAt = now,
            )
          }
          "totalValue" -> {
            val v = numVal ?: return false
            val p = if (sale.quantityKg > 0.0001) SarimLinkEngine.round2(v / sale.quantityKg) else sale.pricePerKg
            sale.copy(
              totalValue = v,
              pricePerKg = p,
              profit = SarimLinkEngine.round2(v - sale.totalCost),
              syncPending = true,
              updatedAt = now,
            )
          }
          "totalCost" -> {
            val c = numVal ?: return false
            sale.copy(
              totalCost = c,
              profit = SarimLinkEngine.round2(sale.totalValue - c),
              syncPending = true,
              updatedAt = now,
            )
          }
          "profit" -> {
            val p = numVal ?: return false
            sale.copy(profit = p, syncPending = true, updatedAt = now)
          }
          else -> {
            val price = numVal ?: return false
            val newTotal = SarimLinkEngine.round2(sale.quantityKg * price)
            sale.copy(
              pricePerKg = price,
              totalValue = newTotal,
              profit = SarimLinkEngine.round2(newTotal - sale.totalCost),
              syncPending = true,
              updatedAt = now,
            )
          }
        }
        dao.upsertSale(updated)
        enqueueOutboxSet(col, updated.id, SarimWebSchema.saleToMap(updated))
        true
      }
      "PRODUCTION" -> {
        val prod = dao.getAllProductionEntries().firstOrNull { it.id == recordId && !it.isDeleted } ?: return false
        if (fieldKey == "newId") {
          val newId = SarimUuid.generateUUID(SarimUuid.PREFIX_PROD, tsMs = now)
          val cloned = prod.copy(id = newId, syncPending = true, updatedAt = now)
          dao.upsertProductionEntry(cloned)
          enqueueOutboxSet(SarimWebSchema.COL_PRODUCTION, cloned.id, SarimWebSchema.productionToMap(cloned))
          dao.softDeleteProductionEntry(prod.id, now)
          enqueueOutboxDelete(
            collection = SarimWebSchema.COL_PRODUCTION,
            docId = prod.id,
            title = "Replaced Duplicate ID: ${prod.storeName}",
            subtitle = prod.id,
            amountText = "${prod.netWeightKg} kg",
            snapshotMap = SarimWebSchema.productionToMap(prod),
          )
          return true
        }
        val updated = when (fieldKey) {
          "dateEpoch" -> {
            val epoch = newValue.toLongOrNull() ?: return false
            val ds = SarimFormat.dateInput(epoch)
            prod.copy(dateEpoch = epoch, dateStr = ds, syncPending = true, updatedAt = now)
          }
          "totalProfit" -> {
            val p = numVal ?: return false
            val perKg = if (prod.netWeightKg > 0.0001) p / prod.netWeightKg else prod.profitPerKg
            prod.copy(totalProfit = p, profitPerKg = perKg, syncPending = true, updatedAt = now)
          }
          "formulaId" -> {
            val formula = dao.getAllFormulas().firstOrNull { it.id == newValue && !it.isDeleted } ?: return false
            val allMats = dao.getAllRawMaterials().filter { !it.isDeleted }.associateBy { it.id }
            val allFormulas = dao.getAllFormulas().filter { !it.isDeleted }.associateBy { it.id }
            val unitCost = FormulaCalculator.computeFormulaSummary(formula, allFormulas, allMats).totalCostPerUnit
            val unitsUsed = prod.formulaUnitsUsed.takeIf { it > 0.0 } ?: 1.0
            val totalFormulaCost = unitsUsed * unitCost
            val costPerKg = if (prod.netWeightKg > 0.0001) totalFormulaCost / prod.netWeightKg else 0.0
            prod.copy(
              formulaId = formula.id,
              formulaName = formula.name,
              formulaUnitsUsed = unitsUsed,
              formulaUnitCost = unitCost,
              totalFormulaCost = totalFormulaCost,
              costPerKg = costPerKg,
              profitPerKg = prod.salePricePerKg - costPerKg,
              totalProfit = prod.totalValue - totalFormulaCost,
              syncPending = true,
              updatedAt = now,
            )
          }
          else -> {
            val gross = numVal ?: return false
            val net = (gross - prod.containerWeightKg).coerceAtLeast(0.0)
            val costPerKg = if (net > 0.0001) prod.totalFormulaCost / net else 0.0
            val totalVal = net * prod.salePricePerKg
            prod.copy(
              grossWeightKg = gross,
              netWeightKg = net,
              costPerKg = costPerKg,
              profitPerKg = prod.salePricePerKg - costPerKg,
              totalValue = totalVal,
              totalProfit = totalVal - prod.totalFormulaCost,
              syncPending = true,
              updatedAt = now,
            )
          }
        }
        dao.upsertProductionEntry(updated)
        enqueueOutboxSet(SarimWebSchema.COL_PRODUCTION, updated.id, SarimWebSchema.productionToMap(updated))
        if (!updated.isReturn) {
          repriceSalesForProductionDay(updated.storeKey, updated.dateStr.ifBlank { SarimFormat.dateInput(updated.dateEpoch) }, now)
        }
        true
      }
      "MATERIAL" -> {
        val mat = dao.getAllRawMaterials().firstOrNull { it.id == recordId && !it.isDeleted } ?: return false
        val valN = numVal ?: return false
        val updated = if (fieldKey == "avgCostPerKg") {
          mat.copy(avgCostPerKg = valN, syncPending = true, updatedAt = now)
        } else {
          mat.copy(stockKg = valN, syncPending = true, updatedAt = now)
        }
        dao.upsertRawMaterial(updated)
        enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updated.id, SarimWebSchema.materialToMap(updated))
        true
      }
      "PAYMENT" -> {
        val tx = dao.getAllPaymentTransactions().firstOrNull { it.id == recordId && !it.isDeleted } ?: return false
        if (fieldKey == "newId") {
          val newId = SarimUuid.generateUUID(SarimUuid.PREFIX_PAY, tsMs = now)
          val cloned = tx.copy(id = newId, syncPending = true, updatedAt = now)
          dao.upsertPaymentTransaction(cloned)
          enqueueOutboxSet(SarimWebSchema.COL_TRANSACTIONS, cloned.id, SarimWebSchema.transactionToMap(cloned))
          dao.softDeletePaymentTransaction(tx.id, now)
          enqueueOutboxDelete(
            collection = SarimWebSchema.COL_TRANSACTIONS,
            docId = tx.id,
            title = "Replaced Duplicate ID: ${tx.entityOrExpenseName}",
            subtitle = tx.id,
            amountText = "Rs ${tx.amount}",
            snapshotMap = SarimWebSchema.transactionToMap(tx),
          )
          return true
        }
        when (fieldKey) {
          "dateEpoch" -> {
            val epoch = newValue.toLongOrNull() ?: return false
            savePaymentTransaction(
              editingId = tx.id,
              dateEpoch = epoch,
              type = tx.type,
              name = tx.entityOrExpenseName,
              amount = tx.amount,
              description = tx.description,
              photoUri = tx.photoUri,
              addToLedger = tx.addToLedger,
              category = tx.category,
            )
          }
          "type" -> {
            val nextType = newValue.trim().uppercase()
            if (nextType !in setOf("EXPENSE", "PAYMENT_IN", "PAYMENT_OUT")) return false
            savePaymentTransaction(
              editingId = tx.id,
              dateEpoch = tx.dateEpoch,
              type = nextType,
              name = tx.entityOrExpenseName,
              amount = tx.amount,
              description = tx.description,
              photoUri = tx.photoUri,
              addToLedger = tx.addToLedger,
              category = tx.category,
            )
          }
          "entityId" -> {
            val ent = dao.getAllPaymentEntities().firstOrNull { it.id == newValue && !it.isDeleted } ?: return false
            savePaymentTransaction(
              editingId = tx.id,
              dateEpoch = tx.dateEpoch,
              type = tx.type,
              name = ent.name,
              amount = tx.amount,
              description = tx.description,
              photoUri = tx.photoUri,
              addToLedger = tx.addToLedger,
              category = tx.category,
            )
          }
          else -> {
            val amt = numVal ?: return false
            savePaymentTransaction(
              editingId = tx.id,
              dateEpoch = tx.dateEpoch,
              type = tx.type,
              name = tx.entityOrExpenseName,
              amount = amt,
              description = tx.description,
              photoUri = tx.photoUri,
              addToLedger = tx.addToLedger,
              category = tx.category,
            )
          }
        }
        true
      }
      "EXPENSE" -> {
        val exp = dao.getAllExpenses().firstOrNull { it.id == recordId && !it.isDeleted } ?: return false
        val linkedTx = dao.getAllPaymentTransactions().firstOrNull { !it.isDeleted && it.expenseId == exp.id }
        val amt = numVal ?: return false
        if (linkedTx != null) {
          savePaymentTransaction(
            editingId = linkedTx.id,
            dateEpoch = linkedTx.dateEpoch,
            type = linkedTx.type,
            name = linkedTx.entityOrExpenseName,
            amount = amt,
            description = linkedTx.description,
            photoUri = linkedTx.photoUri,
            addToLedger = linkedTx.addToLedger,
            category = linkedTx.category,
          )
        } else {
          val updated = exp.copy(amount = amt, syncPending = true, updatedAt = now)
          dao.upsertExpense(updated)
          enqueueOutboxSet(SarimWebSchema.COL_EXPENSES, updated.id, SarimWebSchema.expenseToMap(updated))
        }
        true
      }
      else -> false
    }
  }

  suspend fun deleteAuditRecord(recordType: String, recordId: String): Boolean {
    return when (recordType) {
      "SALE" -> {
        deleteSale(recordId)
        true
      }
      "PRODUCTION" -> {
        deleteProductionEntry(recordId)
        true
      }
      "PAYMENT" -> {
        deletePaymentTransaction(recordId)
        true
      }
      "EXPENSE" -> {
        val linkedTx = dao.getAllPaymentTransactions().firstOrNull { !it.isDeleted && it.expenseId == recordId }
        if (linkedTx != null) {
          deletePaymentTransaction(linkedTx.id)
        } else {
          val now = System.currentTimeMillis()
          val exp = dao.getAllExpenses().firstOrNull { it.id == recordId && !it.isDeleted } ?: return false
          dao.softDeleteExpense(exp.id, now)
          enqueueOutboxDelete(
            collection = SarimWebSchema.COL_EXPENSES,
            docId = exp.id,
            title = exp.name,
            subtitle = "Expense",
            amountText = "Rs ${exp.amount}",
            snapshotMap = SarimWebSchema.expenseToMap(exp),
          )
        }
        true
      }
      else -> false
    }
  }

  private companion object {
    const val SAFE_SNAPSHOT_MAX_CHARS = 700_000
  }
}
