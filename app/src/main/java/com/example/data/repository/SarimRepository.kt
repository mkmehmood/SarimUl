package com.example.data.repository

import com.example.data.local.*
import com.example.data.model.EditHistoryEntry
import com.example.data.model.FormulaIngredient
import com.example.data.model.SarimUuid
import com.example.data.remote.SarimWebSchema
import com.example.data.remote.UuidSyncRegistry
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

  suspend fun initDefaults() {
    SarimSeedHelper.ensureStructuralDefaults(dao)
  }

  private fun toJsonValue(v: Any?): Any = when (v) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().apply {
      v.forEach { (k, innerVal) ->
        if (k != null && innerVal != null) put(k.toString(), toJsonValue(innerVal))
      }
    }
    is Iterable<*> -> JSONArray().apply {
      v.forEach { put(toJsonValue(it)) }
    }
    else -> v
  }

  private fun mapToJsonString(map: Map<String, Any?>): String {
    val obj = JSONObject()
    map.forEach { (k, v) ->
      if (v != null) obj.put(k, toJsonValue(v))
    }
    return obj.toString()
  }

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
    val returned = dao.getAllSettlements().filter { !it.isDeleted && it.returnStoreKey == storeKey }.sumOf { it.returnedKg }
    val sold = dao.getAllSales().filter { !it.isDeleted && !it.isRepSale && it.mode == "SALE" && it.storeKey == storeKey }.sumOf { it.quantityKg }
    val projectedRemaining = producedWithout + addedNetKg + tIn + returned - sold - tOut
    if (projectedRemaining < -0.001) {
      throw IllegalStateException("Remaining store stock cannot drop below zero (sold or transferred stock exceeds new remaining).")
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
  ) {
    val now = System.currentTimeMillis()
    val existing = editingId?.let { id -> dao.getAllProductionEntries().firstOrNull { it.id == id } }
    val netKg = (grossKg - containerKg).coerceAtLeast(0.0)
    if (existing != null && !existing.isReturn && existing.storeKey != store.storeKey) {
      assertStoreRemainingNonNegative(existing.storeKey, existing.id, 0.0)
    }
    assertStoreRemainingNonNegative(store.storeKey, existing?.id, netKg)
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

    val history = existing?.let { JsonCodecs.decodeEditHistory(it.editHistoryJson) }?.toMutableList() ?: mutableListOf()
    if (existing != null) {
      history.add(0, EditHistoryEntry(now, "Updated ${store.name}: ${SarimFormat.formatKg(netKg)}"))
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
      editHistoryJson = JsonCodecs.encodeEditHistory(history.take(25)),
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
  }

  suspend fun deleteProductionEntry(id: String) {
    val now = System.currentTimeMillis()
    val item = dao.getAllProductionEntries().firstOrNull { it.id == id }
    if (item != null && !item.isReturn) {
      assertStoreRemainingNonNegative(item.storeKey, item.id, 0.0)
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
  ) {
    val now = System.currentTimeMillis()
    val cleanMat = materialName.trim()
    val allMats = dao.getAllRawMaterials().filter { !it.isDeleted }
    val existingMat = allMats.firstOrNull { it.name.equals(cleanMat, ignoreCase = true) }
    val totalKg = unitsBought * weightPerUnitKg
    val totalAmount = unitsBought * costPerUnit
    val prevStock = existingMat?.stockKg ?: 0.0
    val prevAvg = existingMat?.avgCostPerKg ?: 0.0
    val newStock = prevStock + totalKg
    val newAvgCost = if (newStock > 0.0001) ((prevStock * prevAvg) + totalAmount) / newStock else 0.0

    var supplierId = existingSupplier?.id
    var supplierNameFinal = existingSupplier?.name
    if (supplierMode == "NEW" && newSupplierName.isNotBlank()) {
      val newEnt = PaymentEntityRecord(
        name = newSupplierName.trim(),
        phone = newSupplierPhone.trim(),
        openingType = "CREDIT",
        openingAmount = 0.0,
        currentBalance = totalAmount,
        type = "payee",
        isSupplier = true,
      )
      dao.upsertPaymentEntity(newEnt)
      enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, newEnt.id, SarimWebSchema.entityToMap(newEnt))
      supplierId = newEnt.id
      supplierNameFinal = newEnt.name
    } else if (supplierMode == "EXISTING" && existingSupplier != null) {
      val updatedSup = existingSupplier.copy(
        currentBalance = existingSupplier.currentBalance + totalAmount,
        isSupplier = true,
        syncPending = true,
        updatedAt = now,
      )
      dao.upsertPaymentEntity(updatedSup)
      enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, updatedSup.id, SarimWebSchema.entityToMap(updatedSup))
    }

    val newPending = (existingMat?.pendingSupplierAmount ?: 0.0) + if (supplierMode != "NONE") totalAmount else 0.0
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

    val purchaseLot = RawMaterialPurchaseEntity(
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
      paymentStatus = if (supplierMode != "NONE") "pending" else "paid",
      totalPayable = if (supplierMode != "NONE") totalAmount else 0.0,
      purchaseDate = SarimFormat.dateInput(dateEpoch),
      updatedAt = now,
    )
    dao.upsertPurchase(purchaseLot)
    enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, purchaseLot.id, SarimWebSchema.purchaseLotToMap(purchaseLot))
  }

  suspend fun deletePurchaseLot(lotId: String) {
    val now = System.currentTimeMillis()
    val lot = dao.getAllPurchases().firstOrNull { it.id == lotId } ?: return
    dao.getAllRawMaterials().firstOrNull { it.id == lot.materialId && !it.isDeleted }?.let { mat ->
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

  suspend fun saveFormula(
    editingId: String?,
    name: String,
    additionalCost: Double,
    costFactor: Double,
    ingredients: List<FormulaIngredient>,
  ) {
    val now = System.currentTimeMillis()
    val existing = editingId?.let { id -> dao.getAllFormulas().firstOrNull { it.id == id } }
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

  suspend fun saveFactoryBatch(
    dateEpoch: Long,
    formula: FormulaEntity,
    units: Double,
    editingId: String? = null,
  ) {
    val now = System.currentTimeMillis()
    val existing = editingId?.let { id -> dao.getAllFactoryBatches().firstOrNull { it.id == id && !it.isDeleted } }

    if (existing != null) {
      val priorFormulas = dao.getAllFormulas().filter { !it.isDeleted }.associateBy { it.id }
      val oldFormula = priorFormulas[existing.formulaId]
      if (oldFormula != null && (oldFormula.unitsAvailable + 0.0001) < existing.unitsProduced) {
        throw IllegalStateException("Some units from this batch have already been consumed.")
      }
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
  ): CustomerEntity {
    val now = System.currentTimeMillis()
    val existing = editingId?.let { id -> dao.getAllCustomers().firstOrNull { it.id == id } }
    val cleanName = name.trim()
    val history = existing?.let { JsonCodecs.decodeEditHistory(it.editHistoryJson) }?.toMutableList() ?: mutableListOf()
    if (existing != null) {
      history.add(0, EditHistoryEntry(now, "Updated details for $cleanName"))
    }
    val deltaOldDebit = oldDebit - (existing?.oldDebit ?: 0.0)
    val entity = (existing ?: CustomerEntity(
      id = SarimUuid.generateUUID(if (isRepCustomer) SarimUuid.PREFIX_REP_CUST else SarimUuid.PREFIX_CUST, tsMs = now),
      name = cleanName,
    )).copy(
      name = cleanName,
      phone = phone.trim(),
      address = address.trim(),
      oldDebit = oldDebit,
      customSalePrice = customSalePrice,
      ledgerPage = ledgerPage.trim().take(12),
      photoUri = photoUri,
      isRepCustomer = isRepCustomer,
      repName = repName.trim(),
      currentCredit = (existing?.currentCredit ?: 0.0) + deltaOldDebit,
      editHistoryJson = JsonCodecs.encodeEditHistory(history.take(25)),
      syncPending = true,
      updatedAt = now,
    )
    dao.upsertCustomer(entity)
    val targetCol = if (isRepCustomer) SarimWebSchema.COL_CLIENTS else SarimWebSchema.COL_CUSTOMERS
    enqueueOutboxSet(targetCol, entity.id, SarimWebSchema.customerToMap(entity))

    if (existing != null && (!existing.name.equals(cleanName, true) || existing.phone != entity.phone)) {
      val saleCol = if (entity.isRepCustomer) SarimWebSchema.COL_REP else SarimWebSchema.COL_SALES
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
      }
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

    // Reverse Customer totals
    dao.getAllCustomers().firstOrNull {
      !it.isDeleted && it.isRepCustomer == sale.isRepSale &&
        (it.id == sale.customerId || it.name.equals(sale.customerName, true))
    }?.let { cust ->
      val creditDelta = when (sale.mode) {
        "SALE" -> if (sale.paymentType == "CREDIT") -sale.totalValue else 0.0
        "COLLECTION" -> sale.amountReceived
        "MONEY_OUT" -> -sale.amountReceived
        "OLD_DEBT" -> -sale.totalValue
        else -> 0.0
      }
      val qtyDelta = if (sale.mode == "SALE") -sale.quantityKg else 0.0
      val valDelta = if (sale.mode == "SALE") -sale.totalValue else 0.0
      val recDelta = when {
        sale.mode == "COLLECTION" -> -sale.amountReceived
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

    // Reverse CHORA if expiredQtyKg > 0
    if (sale.expiredQtyKg > 0.0) {
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
  ) {
    val now = System.currentTimeMillis()
    val existing = editingId?.let { id -> dao.getAllSales().firstOrNull { it.id == id } }
    if (existing != null) {
      applySaleReversalOnDeleteOrEdit(existing, now)
    }

    val allCusts = dao.getAllCustomers().filter { !it.isDeleted }
    var cust = allCusts.firstOrNull {
      it.name.equals(customerName.trim(), ignoreCase = true) && it.isRepCustomer == isRepSale
    }
    if (cust == null) {
      cust = saveCustomer(
        editingId = null,
        name = customerName,
        phone = newCustomerPhone,
        address = "",
        oldDebit = 0.0,
        customSalePrice = null,
        ledgerPage = "",
        photoUri = "",
        isRepCustomer = isRepSale,
        repName = if (isRepSale) repName else "",
      )
    }

    val pricePerKg = cust.customSalePrice ?: (store?.salePricePerKg ?: 400.0)
    val totalValue = when (mode) {
      "SALE" -> quantityKg * pricePerKg
      "COLLECTION" -> amountReceived
      else -> amountReceived
    }
    val totalCost = if (mode == "SALE") quantityKg * costPerKg else 0.0
    val profit = if (mode == "SALE") totalValue - totalCost else 0.0

    val updatedCredit = when (mode) {
      "SALE" -> if (paymentType == "CREDIT") cust.currentCredit + totalValue else cust.currentCredit
      "COLLECTION" -> cust.currentCredit - amountReceived
      "MONEY_OUT" -> cust.currentCredit + amountReceived
      else -> cust.currentCredit
    }

    val updatedCust = cust.copy(
      currentCredit = updatedCredit,
      totalQuantityKg = cust.totalQuantityKg + if (mode == "SALE") quantityKg else 0.0,
      totalSaleValue = cust.totalSaleValue + if (mode == "SALE") totalValue else 0.0,
      totalReceived = cust.totalReceived + if (mode == "COLLECTION") amountReceived else if (mode == "SALE" && paymentType == "CASH") totalValue else 0.0,
      syncPending = true,
      updatedAt = now,
    )
    dao.upsertCustomer(updatedCust)
    val custCol = if (updatedCust.isRepCustomer) SarimWebSchema.COL_CLIENTS else SarimWebSchema.COL_CUSTOMERS
    enqueueOutboxSet(custCol, updatedCust.id, SarimWebSchema.customerToMap(updatedCust))

    if (expiredQtyKg > 0.0) {
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

    val txId = editingId ?: SarimUuid.generateUUID(SarimUuid.PREFIX_SALE, tsMs = now)
    val saleCol = if (isRepSale) SarimWebSchema.COL_REP else SarimWebSchema.COL_SALES

    // FIFO Collection Allocation against unpaid CREDIT / OLD_DEBT sales
    val allocList = mutableListOf<JsonCodecs.AllocationItem>()
    if (mode == "COLLECTION" && amountReceived > 0.0) {
      var remaining = amountReceived
      val openSales = dao.getAllSales()
        .filter {
          !it.isDeleted && it.id != txId && it.isRepSale == isRepSale &&
            (it.customerId == cust.id || it.customerName.equals(cust.name, true)) &&
            !it.creditReceived && (it.mode == "OLD_DEBT" || (it.mode == "SALE" && it.paymentType == "CREDIT"))
        }
        .sortedWith(compareBy<SaleTransactionEntity> { it.dateEpoch }.thenBy { it.createdAt })

      for (parent in openSales) {
        if (remaining <= 0.001) break
        val owed = (parent.totalValue - parent.partialPaymentReceived).coerceAtLeast(0.0)
        if (owed <= 0.001) continue
        val take = minOf(remaining, owed)
        remaining -= take
        val newPartial = parent.partialPaymentReceived + take
        val full = newPartial >= parent.totalValue - 0.01
        val existingChildAllocs = JsonCodecs.decodeCollectionAllocs(parent.collectionAllocsJson).toMutableList()
        existingChildAllocs.removeAll { it.cid == txId }
        existingChildAllocs += JsonCodecs.CollectionAllocItem(cid = txId, amount = take, full = full)
        val updatedParent = parent.copy(
          partialPaymentReceived = newPartial,
          creditReceived = full,
          collectionAllocsJson = JsonCodecs.encodeCollectionAllocs(existingChildAllocs),
          syncPending = true,
          updatedAt = now,
        )
        dao.upsertSale(updatedParent)
        enqueueOutboxSet(saleCol, updatedParent.id, SarimWebSchema.saleToMap(updatedParent))
        allocList += JsonCodecs.AllocationItem(saleId = parent.id, amount = take, full = full)
      }
    }

    val ledgerId = if (addToLedger && ((mode == "SALE" && paymentType == "CASH") || mode == "COLLECTION" || mode == "MONEY_OUT")) {
      existing?.ledgerId ?: SarimUuid.generateUUID(SarimUuid.PREFIX_DAILY_LEDGER, tsMs = now)
    } else null

    val history = existing?.let { JsonCodecs.decodeEditHistory(it.editHistoryJson) }?.toMutableList() ?: mutableListOf()
    if (existing != null) {
      history.add(0, EditHistoryEntry(now, "Edited $mode · ${cust.name}"))
    }

    val expUnitPrice = if (expiredQtyKg > 0.0) pricePerKg else 0.0
    val expVal = expiredQtyKg * expUnitPrice
    val expAllocated = if (expiredQtyKg > 0.0) amountReceived else 0.0

    val saleEntity = SaleTransactionEntity(
      id = txId,
      dateEpoch = dateEpoch,
      mode = mode,
      customerId = cust.id,
      customerName = cust.name,
      repName = repName,
      storeKey = store?.storeKey ?: "",
      storeName = store?.name ?: "",
      quantityKg = quantityKg,
      pricePerKg = pricePerKg,
      totalValue = totalValue,
      totalCost = totalCost,
      profit = profit,
      paymentType = paymentType,
      amountReceived = amountReceived,
      expiredQtyKg = expiredQtyKg,
      returnedQtyKg = returnedQtyKg,
      balanceAfter = updatedCredit,
      addToLedger = addToLedger,
      isRepSale = isRepSale,
      latitude = lat,
      longitude = lng,
      dateStr = SarimFormat.dateInput(dateEpoch),
      timeStr = existing?.timeStr?.takeIf { it.isNotBlank() } ?: SarimFormat.formatTime(now),
      customerPhone = cust.phone,
      creditReceived = (paymentType == "CASH" || mode == "COLLECTION"),
      isCollection = (mode == "COLLECTION"),
      ledgerId = ledgerId,
      notes = notes.trim(),
      transactionType = existing?.transactionType ?: mode,
      costSource = costSource ?: if (mode == "SALE" && store != null) "store" else existing?.costSource,
      affectsInventory = !isRepSale && mode == "SALE",
      isMoneyOut = (mode == "MONEY_OUT"),
      isRepReturn = (paymentType == "REP_RETURN"),
      expiredValue = expVal,
      expiredUnitPrice = expUnitPrice,
      expiredAllocated = expAllocated,
      collectedAmount = if (mode == "COLLECTION" || mode == "MONEY_OUT") amountReceived else 0.0,
      allocationsJson = JsonCodecs.encodeAllocations(allocList),
      partialPaymentReceived = existing?.partialPaymentReceived ?: 0.0,
      collectionAllocsJson = existing?.collectionAllocsJson ?: "[]",
      editHistoryJson = JsonCodecs.encodeEditHistory(history.take(25)),
      createdAt = existing?.createdAt ?: now,
      updatedAt = now,
    )
    dao.upsertSale(saleEntity)
    enqueueOutboxSet(saleCol, saleEntity.id, SarimWebSchema.saleToMap(saleEntity))

    if (ledgerId != null) {
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
            party = cust.name,
            note = "$mode · ${store?.name ?: ""}".trim(' ', '·'),
            category = if (mode == "SALE") "Cash Sale" else if (mode == "COLLECTION") "Collection" else "Customer Payout",
            sourceType = "SALE",
            sourceId = txId,
            dateStr = SarimFormat.dateInput(dateEpoch),
            linkJson = JsonCodecs.encodeLedgerLink(mapOf("saleId" to txId, "isRep" to isRepSale)),
            updatedAt = now,
          )
        )
        touchTimestampKey("daily_ledger_timestamp", SarimWebSchema.COL_DAILY_LEDGER, now)
      }
    }
  }

  suspend fun deleteSale(id: String) {
    val now = System.currentTimeMillis()
    val sale = dao.getAllSales().firstOrNull { it.id == id }
    if (sale != null) {
      applySaleReversalOnDeleteOrEdit(sale, now)
    }
    dao.softDeleteSale(id, now)
    val targetCol = if (sale?.isRepSale == true) SarimWebSchema.COL_REP else SarimWebSchema.COL_SALES
    enqueueOutboxDelete(
      collection = targetCol,
      docId = id,
      title = "${sale?.mode ?: "SALE"}: ${sale?.customerName ?: id}",
      subtitle = "${sale?.quantityKg ?: 0.0} kg",
      amountText = "Rs ${sale?.totalValue ?: 0.0}",
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
  ): PaymentEntityRecord {
    val now = System.currentTimeMillis()
    val existing = editingId?.let { id -> dao.getAllPaymentEntities().firstOrNull { it.id == id } }
    val signedOpening = if (openingType == "CREDIT") openingAmount else -openingAmount
    val prevSigned = existing?.let { if (it.openingType == "CREDIT") it.openingAmount else -it.openingAmount } ?: 0.0
    val delta = signedOpening - prevSigned
    val history = existing?.let { JsonCodecs.decodeEditHistory(it.editHistoryJson) }?.toMutableList() ?: mutableListOf()
    if (existing != null) history.add(0, EditHistoryEntry(now, "Edited entity $name"))
    val rec = (existing ?: PaymentEntityRecord(name = name.trim())).copy(
      name = name.trim(),
      phone = phone.trim(),
      wallet = wallet.trim(),
      ledgerPage = ledgerPage.trim().take(12),
      openingType = openingType,
      openingAmount = openingAmount,
      currentBalance = (existing?.currentBalance ?: 0.0) + delta,
      photoUri = photoUri,
      address = address.trim().ifBlank { existing?.address ?: "" },
      category = category.trim().ifBlank { existing?.category ?: "" },
      notes = notes.trim().ifBlank { existing?.notes ?: "" },
      editHistoryJson = JsonCodecs.encodeEditHistory(history.take(25)),
      type = if (openingType == "DEBIT") "payor" else "payee",
      syncPending = true,
      updatedAt = now,
    )
    dao.upsertPaymentEntity(rec)
    enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, rec.id, SarimWebSchema.entityToMap(rec))

    val pKey = "entity:${rec.id}"
    if (photoUri.isNotBlank()) {
      upsertPhotoKey(pKey, photoUri, now)
    } else if (existing?.photoUri?.isNotBlank() == true) {
      markPhotoDeleted(pKey, now)
    }
    return rec
  }

  suspend fun deletePaymentEntity(id: String) {
    val now = System.currentTimeMillis()
    val e = dao.getAllPaymentEntities().firstOrNull { it.id == id }
    val groupId = SarimUuid.generateUUID("${SarimUuid.PREFIX_GRP}_ent", tsMs = now)

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
    dao.getAllPaymentTransactions().filter { !it.isDeleted && it.entityId == id }.forEach { tx ->
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
  ) {
    val now = System.currentTimeMillis()
    val cleanName = name.trim()
    val dateStr = SarimFormat.dateInput(dateEpoch)
    val existing = editingId?.let { id -> dao.getAllPaymentTransactions().firstOrNull { it.id == id } }

    if (type == "EXPENSE") {
      appendCategoryToKv("categories", category?.takeIf { it.isNotBlank() } ?: cleanName, now)
    }

    val entity = if (type != "EXPENSE") {
      dao.getAllPaymentEntities().firstOrNull { !it.isDeleted && it.name.equals(cleanName, ignoreCase = true) }
        ?: savePaymentEntity(null, cleanName, "", "", "", "CREDIT", 0.0, "")
    } else null

    if (entity != null) {
      val prevImpact = if (existing != null && existing.entityId == entity.id) {
        if (existing.type == "PAYMENT_IN") existing.amount else -existing.amount
      } else 0.0
      val nextImpact = if (type == "PAYMENT_IN") amount else -amount
      val newBal = entity.currentBalance - prevImpact + nextImpact
      val updatedEnt = entity.copy(currentBalance = newBal, syncPending = true, updatedAt = now)
      dao.upsertPaymentEntity(updatedEnt)
      enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, updatedEnt.id, SarimWebSchema.entityToMap(updatedEnt))
    }

    // FIFO supplier payable settlement for PAYMENT_OUT
    val settledMatIds = mutableListOf<String>()
    var isPayableOut = false
    var supplierCreditAmt = 0.0
    if (type == "PAYMENT_OUT" && entity != null && existing == null) {
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
    } else if (type == "PAYMENT_IN" && entity?.isSupplier == true) {
      supplierCreditAmt = amount
    }

    val txId = editingId ?: SarimUuid.generateUUID(SarimUuid.PREFIX_PAY, tsMs = now)
    val ledgerId = if (addToLedger && amount > 0.0) (existing?.ledgerId ?: SarimUuid.generateUUID(SarimUuid.PREFIX_DAILY_LEDGER, tsMs = now)) else null
    val expId = existing?.expenseId ?: if (type == "EXPENSE") txId else SarimUuid.generateUUID(SarimUuid.PREFIX_EXP, tsMs = now)

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
    if (existing != null) history.add(0, EditHistoryEntry(now, "Edited $type · $cleanName"))

    val txEntity = PaymentTransactionEntity(
      id = txId,
      dateEpoch = dateEpoch,
      type = type,
      entityOrExpenseName = cleanName,
      entityId = entity?.id,
      amount = amount,
      description = description.trim(),
      photoUri = photoUri,
      addToLedger = addToLedger,
      dateStr = dateStr,
      timeStr = existing?.timeStr?.takeIf { it.isNotBlank() } ?: SarimFormat.formatTime(now),
      isExpense = (type == "EXPENSE"),
      expenseId = expenseRec.id,
      isPayable = existing?.isPayable ?: isPayableOut,
      ledgerId = ledgerId,
      entityType = entity?.type ?: if (type == "EXPENSE") "expense" else "",
      materialId = settledMatIds.firstOrNull() ?: existing?.materialId,
      materialIdsJson = if (settledMatIds.isNotEmpty()) JsonCodecs.encodeStrings(settledMatIds.distinct()) else (existing?.materialIdsJson ?: "[]"),
      consumedCreditsJson = existing?.consumedCreditsJson ?: "[]",
      category = if (type == "EXPENSE") (category?.takeIf { it.isNotBlank() } ?: cleanName) else existing?.category,
      supplierCreditAmount = if (supplierCreditAmt > 0.0) supplierCreditAmt else (existing?.supplierCreditAmount ?: 0.0),
      editHistoryJson = JsonCodecs.encodeEditHistory(history.take(25)),
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

    if (ledgerId != null) {
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

  suspend fun deletePaymentTransaction(id: String) {
    val now = System.currentTimeMillis()
    val tx = dao.getAllPaymentTransactions().firstOrNull { it.id == id }
    val groupId = if (tx?.expenseId != null) SarimUuid.generateUUID("${SarimUuid.PREFIX_GRP}_pay", tsMs = now) else null

    if (tx != null) {
      if (!tx.entityId.isNullOrBlank()) {
        dao.getAllPaymentEntities().firstOrNull { it.id == tx.entityId && !it.isDeleted }?.let { ent ->
          val reversedBal = if (tx.type == "PAYMENT_IN") ent.currentBalance - tx.amount else ent.currentBalance + tx.amount
          val updatedEnt = ent.copy(currentBalance = reversedBal, syncPending = true, updatedAt = now)
          dao.upsertPaymentEntity(updatedEnt)
          enqueueOutboxSet(SarimWebSchema.COL_ENTITIES, updatedEnt.id, SarimWebSchema.entityToMap(updatedEnt))
        }
      }

      if (tx.isPayable) {
        val matIds = (JsonCodecs.decodeStrings(tx.materialIdsJson) + listOfNotNull(tx.materialId)).distinct()
        if (matIds.isNotEmpty()) {
          dao.getAllRawMaterials().firstOrNull { it.id == matIds.first() && !it.isDeleted }?.let { mat ->
            val updatedMat = mat.copy(
              pendingSupplierAmount = mat.pendingSupplierAmount + tx.amount,
              paymentStatus = "pending",
              syncPending = true,
              updatedAt = now,
            )
            dao.upsertRawMaterial(updatedMat)
            enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, updatedMat.id, SarimWebSchema.materialToMap(updatedMat))
          }
        }
      }

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
  ) {
    val now = System.currentTimeMillis()
    val existing = editingId?.let { id -> dao.getAllDailyLedger().firstOrNull { it.id == id } }
    val resolvedCat = category?.takeIf { it.isNotBlank() }
      ?: if (type == "TRANSFER") "Account Transfer" else if (party.isNotBlank()) party.trim() else (existing?.category ?: "General")
    dao.upsertLedgerEntry(
      DailyLedgerEntity(
        id = editingId ?: SarimUuid.generateUUID(SarimUuid.PREFIX_DAILY_LEDGER, tsMs = now),
        dateEpoch = dateEpoch,
        type = type,
        amount = amount,
        mode = mode.trim().ifBlank { "Cash" },
        toMode = if (type == "TRANSFER") toMode.trim() else "",
        party = if (type == "TRANSFER") "" else party.trim(),
        refNo = refNo.trim(),
        note = note.trim(),
        category = resolvedCat,
        sourceType = existing?.sourceType ?: "MANUAL",
        sourceId = existing?.sourceId ?: "",
        scope = scope.ifBlank { existing?.scope ?: "" },
        dateStr = SarimFormat.dateInput(dateEpoch),
        linkJson = existing?.linkJson ?: "",
        createdAt = existing?.createdAt ?: now,
        updatedAt = now,
      )
    )
    touchTimestampKey("daily_ledger_timestamp", SarimWebSchema.COL_DAILY_LEDGER, now)
  }

  suspend fun deleteDailyLedgerEntry(id: String) {
    val now = System.currentTimeMillis()
    val l = dao.getAllDailyLedger().firstOrNull { it.id == id }
    dao.softDeleteLedgerEntry(id, now)
    recordSingletonDeletion(
      collection = SarimWebSchema.COL_DAILY_LEDGER,
      docId = id,
      title = l?.party?.ifBlank { l.mode } ?: "Ledger Entry",
      subtitle = l?.category ?: l?.type ?: "",
      amountText = "Rs ${l?.amount ?: 0.0}",
      snapshotMap = l?.let { SarimWebSchema.dailyLedgerEntryToMap(it) },
    )
  }

  suspend fun saveCalculatorSettlement(settlement: CalculatorSettlementEntity) {
    val now = System.currentTimeMillis()
    val existing = dao.getAllSettlements().firstOrNull { it.id == settlement.id && !it.isDeleted }
    if (existing != null) {
      // Unlink prior sales tied to this settlement before re-reconciling
      dao.getAllSales().filter { !it.isDeleted && it.usedInCalcId == existing.id }.forEach { sale ->
        val col = if (sale.isRepSale) SarimWebSchema.COL_REP else SarimWebSchema.COL_SALES
        val unlinked = sale.copy(usedInCalcId = null, syncPending = true, updatedAt = now)
        dao.upsertSale(unlinked)
        enqueueOutboxSet(col, unlinked.id, SarimWebSchema.saleToMap(unlinked))
      }
      // Reverse prior return log and return production entry
      existing.returnLogId?.takeIf { it.isNotBlank() }?.let { rLogId ->
        dao.softDeleteReturnLog(rLogId, now)
      }
      existing.returnEntryId?.takeIf { it.isNotBlank() }?.let { rEntryId ->
        dao.softDeleteProductionEntry(rEntryId, now)
      }
      // Reverse prior CHORA adjustment
      if (existing.expiredKg > 0.0 && existing.expiredApplied) {
        dao.getAllRawMaterials().firstOrNull { it.name.equals("CHORA", ignoreCase = true) && !it.isDeleted }?.let { chora ->
          val revertedChora = chora.copy(
            stockKg = (chora.stockKg - existing.expiredKg).coerceAtLeast(0.0),
            collectedKg = (chora.collectedKg - existing.expiredKg).coerceAtLeast(0.0),
            syncPending = true,
            updatedAt = now,
          )
          dao.upsertRawMaterial(revertedChora)
          enqueueOutboxSet(SarimWebSchema.COL_INVENTORY, revertedChora.id, SarimWebSchema.materialToMap(revertedChora))
        }
      }
      // Soft-delete prior ledger rows created by this settlement
      var touchedOldLedger = false
      dao.getAllDailyLedger().filter { !it.isDeleted && it.sourceType == "CALCULATOR" && it.sourceId == existing.id }.forEach { l ->
        dao.softDeleteLedgerEntry(l.id, now)
        touchedOldLedger = true
      }
      if (touchedOldLedger) touchTimestampKey("daily_ledger_timestamp", SarimWebSchema.COL_DAILY_LEDGER, now)
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

    var returnLogId: String? = if (existing == null) settlement.returnLogId else null
    var returnEntryId: String? = if (existing == null) settlement.returnEntryId else null
    if (settlement.returnedKg > 0.0 && settlement.returnStoreKey.isNotBlank()) {
      val log = ReturnLogEntity(
        id = returnLogId ?: SarimUuid.generateUUID(SarimUuid.PREFIX_RETLOG, tsMs = now),
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
        id = returnEntryId ?: SarimUuid.generateUUID(SarimUuid.PREFIX_RET, tsMs = now),
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
    }

    // Reconcile matching unsettled sales in settlement range
    val fromStart = SarimFormat.startOfDay(settlement.fromDateEpoch)
    val toEnd = SarimFormat.endOfDay(settlement.toDateEpoch)
    val linkedSalesIds = mutableListOf<String>()
    val linkedRepSalesIds = mutableListOf<String>()
    val linkedRepReturnIds = mutableListOf<String>()

    dao.getAllSales().filter {
      !it.isDeleted && it.usedInCalcId.isNullOrBlank() &&
        it.repName.equals(settlement.repName, ignoreCase = true) &&
        it.dateEpoch in fromStart..toEnd
    }.forEach { s ->
      val col = if (s.isRepSale) SarimWebSchema.COL_REP else SarimWebSchema.COL_SALES
      val updatedSale = s.copy(
        usedInCalcId = settlement.id,
        creditReceived = if (!s.isRepSale && s.paymentType == "CREDIT") true else s.creditReceived,
        syncPending = true,
        updatedAt = now,
      )
      dao.upsertSale(updatedSale)
      enqueueOutboxSet(col, updatedSale.id, SarimWebSchema.saleToMap(updatedSale))
      if (s.isRepSale) {
        if (s.paymentType == "REP_RETURN" || s.isRepReturn) linkedRepReturnIds += s.id
        else linkedRepSalesIds += s.id
      } else {
        linkedSalesIds += s.id
      }
    }

    val updatedSettlement = settlement.copy(
      dateStr = dateStr,
      returnLogId = returnLogId,
      returnEntryId = returnEntryId,
      statusClass = statusClass,
      returnRep = if (settlement.returnedKg > 0.0) settlement.repName else settlement.returnRep,
      returnType = if (settlement.returnedKg > 0.0) "store" else settlement.returnType,
      expiredApplied = settlement.expiredKg > 0.0,
      linkedSalesIdsJson = if (linkedSalesIds.isNotEmpty()) JsonCodecs.encodeStrings(linkedSalesIds) else settlement.linkedSalesIdsJson,
      linkedRepSalesIdsJson = if (linkedRepSalesIds.isNotEmpty()) JsonCodecs.encodeStrings(linkedRepSalesIds) else settlement.linkedRepSalesIdsJson,
      linkedRepReturnIdsJson = if (linkedRepReturnIds.isNotEmpty()) JsonCodecs.encodeStrings(linkedRepReturnIds) else settlement.linkedRepReturnIdsJson,
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

    // Unlink usedInCalcId across sales & rep
    dao.getAllSales().filter { !it.isDeleted && it.usedInCalcId == s.id }.forEach { sale ->
      val col = if (sale.isRepSale) SarimWebSchema.COL_REP else SarimWebSchema.COL_SALES
      val unlinked = sale.copy(usedInCalcId = null, syncPending = true, updatedAt = now)
      dao.upsertSale(unlinked)
      enqueueOutboxSet(col, unlinked.id, SarimWebSchema.saleToMap(unlinked))
    }

    // Reverse linked return log & return production record
    s.returnLogId?.takeIf { it.isNotBlank() }?.let { rLogId ->
      val log = dao.getAllReturns().firstOrNull { it.id == rLogId }
      dao.softDeleteReturnLog(rLogId, now)
      enqueueOutboxDelete(
        collection = SarimWebSchema.COL_RETURNS,
        docId = rLogId,
        title = "Return: ${log?.seller ?: s.repName}",
        subtitle = "${s.returnedKg} kg",
        amountText = "${s.returnedKg} kg",
        snapshotMap = log?.let { SarimWebSchema.returnLogToMap(it) },
        groupId = groupId,
      )
    }
    s.returnEntryId?.takeIf { it.isNotBlank() }?.let { rEntryId ->
      val prodRet = dao.getAllProductionEntries().firstOrNull { it.id == rEntryId }
      dao.softDeleteProductionEntry(rEntryId, now)
      enqueueOutboxDelete(
        collection = SarimWebSchema.COL_PRODUCTION,
        docId = rEntryId,
        title = "Return: ${s.repName}",
        subtitle = "${s.returnedKg} kg",
        amountText = "${s.returnedKg} kg",
        snapshotMap = prodRet?.let { SarimWebSchema.productionToMap(it) },
        groupId = groupId,
      )
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

    // Soft delete linked Daily Ledger entries
    var touchedLedger = false
    dao.getAllDailyLedger().filter { !it.isDeleted && it.sourceType == "CALCULATOR" && it.sourceId == s.id }.forEach { l ->
      dao.softDeleteLedgerEntry(l.id, now)
      touchedLedger = true
    }
    if (touchedLedger) touchTimestampKey("daily_ledger_timestamp", SarimWebSchema.COL_DAILY_LEDGER, now)

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
      dateEpoch = dateEpoch,
      fromStoreKey = from.storeKey,
      fromStoreName = from.name,
      toStoreKey = to.storeKey,
      toStoreName = to.name,
      quantityKg = qtyKg,
      note = note.trim(),
      dateStr = SarimFormat.dateInput(dateEpoch),
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
      dateEpoch = dateEpoch,
      fromEntityId = from.id,
      fromEntityName = from.name,
      toEntityId = to.id,
      toEntityName = to.name,
      amount = amount,
      note = note.trim(),
      photoUri = photoUri,
      dateStr = SarimFormat.dateInput(dateEpoch),
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

  suspend fun closeFinancialYear(): Int {
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
    val compacted = dao.getAllSales().size + dao.getAllProductionEntries().size +
      dao.getAllPaymentTransactions().size + dao.getAllDailyLedger().size +
      dao.getAllSettlements().size + dao.getAllExpenses().size + dao.getAllReturns().size
    dao.clearSales()
    dao.clearProductionEntries()
    dao.clearPaymentTransactions()
    dao.clearExpenses()
    dao.clearReturns()
    dao.clearDailyLedger()
    dao.clearSettlements()
    dao.clearStockTransfers()
    dao.clearPaymentTransfers()
    dao.clearFactoryBatches()
    dao.clearPurchases()
    touchTimestampKey("daily_ledger_timestamp", SarimWebSchema.COL_DAILY_LEDGER, now)

    val (prevCount, _, _) = readYearCloseMeta()
    val nextCount = prevCount + 1
    val closedDateStr = SarimFormat.dateInput(now)
    val settingsObj = JSONObject().apply {
      put("fyCloseCount", nextCount)
      put("lastYearClosedAt", now)
      put("lastYearClosedDate", closedDateStr)
    }
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
    return compacted
  }

  private companion object {
    const val SAFE_SNAPSHOT_MAX_CHARS = 700_000
  }
}
