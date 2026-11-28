package com.example

import com.example.data.local.*
import com.example.data.model.FormulaIngredient
import com.example.data.model.SarimUuid
import com.example.data.remote.InMemoryKvStorePersistence
import com.example.data.remote.UuidSyncRegistry
import com.example.data.repository.RecycleBinRecord
import com.example.data.repository.SarimLinkEngine
import com.example.data.repository.SarimRecycleRestoreEngine
import com.example.data.repository.SarimRepository
import com.example.util.SarimFormat
import com.example.viewmodel.SarimMetricsEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SarimLinksAndGuardsTest {

  private lateinit var dao: InMemorySarimDao
  private lateinit var repo: SarimRepository
  private lateinit var restoreEngine: SarimRecycleRestoreEngine

  @Before
  fun setUp() {
    dao = InMemorySarimDao()
    val safeReader = SafeSqliteCacheReader(dao)
    val uuidReg = UuidSyncRegistry(InMemoryKvStorePersistence())
    repo = SarimRepository(dao, safeReader, uuidReg)
    restoreEngine = SarimRecycleRestoreEngine(dao, uuidReg)
  }

  @Test
  fun pickReturnsCovered_prefersExactSubsetSumBeforeGreedyFallback() {
    val t1 = 1_735_700_000_000L
    val r1 = SaleTransactionEntity(id = "r1", dateEpoch = t1, mode = "REP_RETURN", customerId = "", customerName = "RepA", returnedQtyKg = 5.0, expiredQtyKg = 2.0, isRepReturn = true)
    val r2 = SaleTransactionEntity(id = "r2", dateEpoch = t1 + 1000, mode = "REP_RETURN", customerId = "", customerName = "RepA", returnedQtyKg = 3.0, expiredQtyKg = 1.0, isRepReturn = true)
    val r3 = SaleTransactionEntity(id = "r3", dateEpoch = t1 + 2000, mode = "REP_RETURN", customerId = "", customerName = "RepA", returnedQtyKg = 2.0, expiredQtyKg = 1.0, isRepReturn = true)
    // Exact subset matching returned=5, expired=2 -> r2+r3 (3+2=5, 1+1=2) or r1 (5, 2). r1 is earlier chronologically!
    val picked = SarimLinkEngine.pickReturnsCovered(listOf(r1, r2, r3), retTarget = 5.0, expTarget = 2.0)
    assertEquals(listOf("r1"), picked.map { it.id })

    // Subset only achievable via r1 + r3 (7 kg ret, 3 kg exp)
    val pickedPair = SarimLinkEngine.pickReturnsCovered(listOf(r1, r2, r3), retTarget = 7.0, expTarget = 3.0)
    assertEquals(listOf("r1", "r3"), pickedPair.map { it.id })
  }

  @Test
  fun ledgerBidirectionalLinks_resolveAndCascadeAndLockTabRows() = runBlocking {
    val now = System.currentTimeMillis()
    repo.savePaymentEntity(null, "Vendor Alpha", "03001234567", "Cash", "P1", "CREDIT", 1000.0, "")
    repo.saveCustomer(null, "Ali Traders", "03009876543", "Mardan", 500.0, null, "C1", "", false, "")

    // Ambiguous target when party exists as both entity and customer
    repo.saveCustomer(null, "Dual Party", "03111111111", "", 200.0, null, "", "", false, "")
    repo.savePaymentEntity(null, "Dual Party", "03111111111", "", "", "CREDIT", 300.0, "")
    val ambig = repo.resolveLedgerTargetForInput("OUT", "Business", "Dual Party", "General Expense")
    assertTrue(ambig is SarimLinkEngine.LedgerLinkTarget.AmbiguousTarget)

    // Save manual ledger OUT linked explicitly to Entity "Dual Party"
    repo.saveDailyLedgerEntry(
      editingId = null,
      dateEpoch = now,
      type = "OUT",
      amount = 150.0,
      mode = "Cash",
      toMode = "",
      party = "Dual Party",
      refNo = "",
      note = "Paid supplier",
      category = "Supplier Payment",
      scope = "Business",
      linkedTargetChoice = "ENTITY",
    )
    val entityAfter = dao.getAllPaymentEntities().first { it.name == "Dual Party" && !it.isDeleted }
    assertEquals(150.0, entityAfter.currentBalance, 0.01) // 300 - 150
    val createdTx = dao.getAllPaymentTransactions().first { !it.isDeleted && it.entityId == entityAfter.id }
    val ledgerRow = dao.getAllDailyLedger().first { !it.isDeleted && it.party == "Dual Party" }
    assertFalse(SarimLinkEngine.isTabOwnedLedgerRow(ledgerRow))

    // Deleting manual linked ledger entry reverses child payment transaction & restores entity balance
    repo.deleteDailyLedgerEntry(ledgerRow.id)
    val entityRestored = dao.getAllPaymentEntities().first { it.id == entityAfter.id }
    assertEquals(300.0, entityRestored.currentBalance, 0.01)
    assertTrue(dao.getAllPaymentTransactions().first { it.id == createdTx.id }.isDeleted)

    // Save manual ledger IN linked to direct Customer "Ali Traders"
    repo.saveDailyLedgerEntry(
      editingId = null,
      dateEpoch = now,
      type = "IN",
      amount = 200.0,
      mode = "Cash",
      toMode = "",
      party = "Ali Traders",
      refNo = "",
      note = "Cash collection via ledger",
      category = "Debt Collection",
      scope = "Business",
    )
    val aliAfterIn = dao.getAllCustomers().first { it.name == "Ali Traders" && !it.isDeleted }
    assertEquals(300.0, aliAfterIn.currentCredit, 0.01)

    // Tab-owned row created from Payments tab is locked against edit/delete in Daily Ledger
    val tabTx = repo.savePaymentTransaction(
      editingId = null,
      dateEpoch = now,
      type = "EXPENSE",
      name = "Fuel",
      amount = 500.0,
      description = "Diesel",
      photoUri = "",
      addToLedger = true,
      category = "Fuel",
    )
    val tabLedger = dao.getAllDailyLedger().first { !it.isDeleted && it.id == tabTx.ledgerId }
    assertTrue(SarimLinkEngine.isTabOwnedLedgerRow(tabLedger))
    assertThrows(IllegalStateException::class.java) {
      runBlocking { repo.deleteDailyLedgerEntry(tabLedger.id) }
    }
    assertThrows(IllegalStateException::class.java) {
      runBlocking {
        repo.saveDailyLedgerEntry(
          editingId = tabLedger.id,
          dateEpoch = now,
          type = "OUT",
          amount = 600.0,
          mode = "Cash",
          toMode = "",
          party = "Fuel",
          refNo = "",
          note = "",
        )
      }
    }
    Unit
  }

  @Test
  fun auditAndRepairSaleLinks_cleansOrphansAndRecomputesPartialAndCustomerDebt() = runBlocking {
    val now = System.currentTimeMillis()
    val store = StoreEntity(storeKey = "zubair", name = "Zubair Store", formulaId = "f1", salePricePerKg = 400.0)
    dao.upsertStore(store)
    repo.saveCustomer(null, "Bilal", "0300", "", 0.0, null, "", "", false, "")

    val creditSale = repo.saveSaleOrCollection(
      editingId = null,
      dateEpoch = now - 86_400_000L,
      mode = "SALE",
      customerName = "Bilal",
      newCustomerPhone = "",
      repName = "Direct",
      store = store,
      quantityKg = 10.0,
      costPerKg = 200.0,
      paymentType = "CREDIT",
      amountReceived = 0.0,
      expiredQtyKg = 0.0,
      returnedQtyKg = 0.0,
      addToLedger = false,
      isRepSale = false,
    )
    val collection = repo.saveSaleOrCollection(
      editingId = null,
      dateEpoch = now,
      mode = "COLLECTION",
      customerName = "Bilal",
      newCustomerPhone = "",
      repName = "Direct",
      store = null,
      quantityKg = 0.0,
      costPerKg = 0.0,
      paymentType = "COLLECTION",
      amountReceived = 1500.0,
      expiredQtyKg = 0.0,
      returnedQtyKg = 0.0,
      addToLedger = false,
      isRepSale = false,
    )

    // Deleting credit sale while collectionAllocs has an entry must throw
    assertThrows(IllegalStateException::class.java) {
      runBlocking { repo.deleteSale(creditSale.id) }
    }
    // Converting credit sale with collectionAllocs to CASH must throw
    assertThrows(IllegalStateException::class.java) {
      runBlocking {
        repo.saveSaleOrCollection(
          editingId = creditSale.id,
          dateEpoch = now - 86_400_000L,
          mode = "SALE",
          customerName = "Bilal",
          newCustomerPhone = "",
          repName = "Direct",
          store = store,
          quantityKg = 10.0,
          costPerKg = 200.0,
          paymentType = "CASH",
          amountReceived = 0.0,
          expiredQtyKg = 0.0,
          returnedQtyKg = 0.0,
          addToLedger = false,
          isRepSale = false,
        )
      }
    }

    // Simulate cloud corruption: soft-delete collection directly without clearing parent creditSale.collectionAllocsJson
    dao.softDeleteSale(collection.id, now)
    val issues = SarimLinkEngine.auditLinks(dao.getAllSales())
    assertTrue(issues.any { it.kind == "link_orphan" && it.recordId == creditSale.id })

    val snap = SarimMetricsEngine.compute(
      stores = listOf(store),
      materials = emptyList(),
      formulas = emptyList(),
      production = emptyList(),
      sales = dao.getAllSales(),
      customers = dao.getAllCustomers(),
      entities = emptyList(),
      payments = emptyList(),
      ledger = emptyList(),
      settlements = emptyList(),
      stockTransfers = emptyList(),
      accounts = emptyList(),
      overviewMonths = 6,
    )
    assertTrue(snap.auditFindings.any { it.recordType == "LINK_REPAIR" && it.recordId == creditSale.id })

    val fixedCount = repo.repairSaleLinks()
    assertTrue(fixedCount >= 1)
    val repairedCredit = dao.getAllSales().first { it.id == creditSale.id }
    assertEquals(0.0, repairedCredit.partialPaymentReceived, 0.01)
    assertFalse(repairedCredit.creditReceived)
    assertTrue(JsonCodecs.decodeCollectionAllocs(repairedCredit.collectionAllocsJson).isEmpty())
    val bilal = dao.getAllCustomers().first { it.name == "Bilal" && !it.isDeleted }
    assertEquals(4000.0, bilal.currentCredit, 0.01)
  }

  @Test
  fun guards_enforceStoreFormulaBatchPurchaseLotProductionAndCashRules() = runBlocking {
    val now = System.currentTimeMillis()
    repo.saveRawMaterialPurchase("Tobacco", now, "bag", 1.0, 50.0, 5000.0, "NONE", null, "", "")
    val mat = dao.getAllRawMaterials().first { it.name == "Tobacco" && !it.isDeleted }
    val lot = dao.getAllPurchases().first { it.materialId == mat.id && !it.isDeleted }

    repo.saveFormula(
      editingId = null,
      name = "Mix A",
      additionalCost = 100.0,
      costFactor = 1.0,
      ingredients = listOf(FormulaIngredient(refType = "MATERIAL", refId = mat.id, refName = mat.name, qtyKg = 10.0)),
    )
    val f = dao.getAllFormulas().first { it.name == "MIX A" && !it.isDeleted }
    repo.saveStore("MAIN", f.id, 400.0)
    val store = dao.getAllStores().first { !it.isDeleted }

    // Formula delete blocked while store uses it
    assertThrows(IllegalStateException::class.java) {
      runBlocking { repo.deleteFormula(f.id) }
    }

    // Batch exceeding available raw stock (6 units * 10kg = 60kg > 50kg) must throw without mutating stock
    assertThrows(IllegalStateException::class.java) {
      runBlocking { repo.saveFactoryBatch(now, f, 6.0) }
    }
    assertEquals(50.0, dao.getAllRawMaterials().first { it.id == mat.id }.stockKg, 0.01)

    // Produce 2 units (consumes 20kg Tobacco -> 30kg remaining)
    repo.saveFactoryBatch(now, f, 2.0)
    val batch = dao.getAllFactoryBatches().first { !it.isDeleted }
    assertEquals(30.0, dao.getAllRawMaterials().first { it.id == mat.id }.stockKg, 0.01)

    // Purchase lot delete blocked because 20kg of 50kg was consumed
    assertThrows(IllegalStateException::class.java) {
      runBlocking { repo.deletePurchaseLot(lot.id) }
    }

    // Production entry using 1 unit -> 1 unit remains available on formula
    val fAfterBatch = dao.getAllFormulas().first { it.id == f.id }
    repo.saveProductionEntry(null, now, store, fAfterBatch, 52.0, 2.0, 1.0, 1100.0, emptyList())
    val prod = dao.getAllProductionEntries().first { !it.isDeleted }

    // Production using 2 units when only 1 available must throw (not clamp to 0)
    val fAfterProd = dao.getAllFormulas().first { it.id == f.id }
    assertThrows(IllegalStateException::class.java) {
      runBlocking { repo.saveProductionEntry(null, now, store, fAfterProd, 52.0, 2.0, 2.0, 1100.0, emptyList()) }
    }

    // Factory batch delete blocked because 1 of 2 produced units was consumed by production
    assertThrows(IllegalStateException::class.java) {
      runBlocking { repo.deleteFactoryBatch(batch.id) }
    }

    // Sale of 10kg on same day reprices when production gross weight changes
    val sale = repo.saveSaleOrCollection(
      editingId = null,
      dateEpoch = now,
      mode = "SALE",
      customerName = "Zaid",
      newCustomerPhone = "",
      repName = "Direct",
      store = store,
      quantityKg = 10.0,
      costPerKg = prod.costPerKg,
      paymentType = "CASH",
      amountReceived = 0.0,
      expiredQtyKg = 0.0,
      returnedQtyKg = 0.0,
      addToLedger = false,
      isRepSale = false,
    )
    // Deleting store blocked while production/sales exist
    assertThrows(IllegalStateException::class.java) {
      runBlocking { repo.deleteStore(store.storeKey) }
    }

    // Reprice check via audit fix doubling gross weight (costPerKg halves)
    repo.applyAuditFix("PRODUCTION", prod.id, 102.0) // net goes 50 -> 100
    val repricedSale = dao.getAllSales().first { it.id == sale.id }
    assertTrue(repricedSale.totalCost < sale.totalCost)

    // Cash guard on PAYMENT_IN delete: add cash IN 1000 then spend it via EXPENSE so netCash drops -> deleting PAYMENT_IN blocked
    val pIn = repo.savePaymentTransaction(null, now, "PAYMENT_IN", "Investor", 1000.0, "", "", false)
    val breakBeforeSpend = SarimLinkEngine.computeCashInHandBreakdown(dao.getAllSales(), dao.getAllSettlements(), dao.getAllPaymentTransactions(), dao.getAllFactoryBatches())
    // Spend all cash + leave < pIn.amount remaining net cash
    repo.savePaymentTransaction(null, now, "EXPENSE", "Rent", breakBeforeSpend.netCash, "", "", false)
    assertThrows(IllegalStateException::class.java) {
      runBlocking { repo.deletePaymentTransaction(pIn.id) }
    }
    Unit
  }

  @Test
  fun recycleBinRestore_replaysGroupEffectsAndBlocksOrphanCalcChildren() = runBlocking {
    val now = System.currentTimeMillis()
    repo.saveRawMaterialPurchase("Leaf", now, "kg", 10.0, 1.0, 100.0, "NONE", null, "", "")
    val mat = dao.getAllRawMaterials().first { it.name == "Leaf" && !it.isDeleted }
    repo.saveFormula(
      editingId = null,
      name = "F1",
      additionalCost = 50.0,
      costFactor = 1.0,
      ingredients = listOf(FormulaIngredient(refType = "MATERIAL", refId = mat.id, refName = mat.name, qtyKg = 5.0)),
    )
    val formula = dao.getAllFormulas().first { it.name == "F1" && !it.isDeleted }
    repo.saveStore("ZUBAIR", formula.id, 400.0)
    val store = dao.getAllStores().first { !it.isDeleted }

    // Batch -> consumes 5kg Leaf, adds 1 unit to F1
    repo.saveFactoryBatch(now, formula, 1.0)
    val batch = dao.getAllFactoryBatches().first { !it.isDeleted }
    assertEquals(5.0, dao.getAllRawMaterials().first { it.id == mat.id }.stockKg, 0.01)

    // Delete batch -> restores 5kg Leaf to 10kg and 0 units available
    repo.deleteFactoryBatch(batch.id)
    assertEquals(10.0, dao.getAllRawMaterials().first { it.id == mat.id }.stockKg, 0.01)
    assertEquals(0.0, dao.getAllFormulas().first { it.id == formula.id }.unitsAvailable, 0.01)

    // Restore batch from Recycle Bin -> deducts 5kg Leaf again & adds 1 unit to F1
    restoreEngine.restoreRecycleItem(
      RecycleBinRecord(batch.id, "Factory Tab", "Factory Batch", "1 units", "Rs 550", now, "BATCH")
    )
    assertEquals(5.0, dao.getAllRawMaterials().first { it.id == mat.id }.stockKg, 0.01)
    assertEquals(1.0, dao.getAllFormulas().first { it.id == formula.id }.unitsAvailable, 0.01)

    // Calc settlement with transferSaleId -> deleting settlement tombstones both settlement and transferSaleId with shared groupId
    repo.saveCustomer(null, "RepB", "", "", 0.0, null, "", "", false, "")
    val deliv = repo.saveSaleOrCollection(null, now, "SALE", "RepB", "", "RepB", store, 10.0, 200.0, "CREDIT", 0.0, 0.0, 0.0, false, false)
    val calc = CalculatorSettlementEntity(
      id = SarimUuid.generateUUID(SarimUuid.PREFIX_CALC, tsMs = now),
      repName = "RepB",
      fromDateEpoch = SarimFormat.startOfDay(now),
      toDateEpoch = now,
      dateEpoch = now,
      totalSoldKg = 10.0,
      returnedKg = 2.0,
      expiredKg = 0.0,
      sharedDeductionKg = 0.0,
      netSoldKg = 8.0,
      returnStoreKey = "",
      creditSalesQty = 0.0,
      commissionPerUnit = 10.0,
      commissionPaid = 0.0,
      recoveredCredit = 0.0,
      fieldExpenses = 0.0,
      receivedCash = 3200.0,
      expectedCash = 3200.0,
      status = "OK",
      statusDiff = 0.0,
      grossCommission = 80.0,
      commissionPayable = 80.0,
      totalCost = 2000.0,
      grossRevenue = 3200.0,
      netProfit = 1120.0,
      creditIssuedValue = 0.0,
      returnType = "TRANSFER",
      returnRep = "RepA",
      addToLedger = false,
    )
    repo.saveCalculatorSettlement(calc)
    val savedCalc = dao.getAllSettlements().first { it.id == calc.id }
    val trSaleId = savedCalc.transferSaleId!!
    assertTrue(dao.getAllSales().first { it.id == deliv.id }.creditReceived)

    // Direct delete on Calc transfer sale is blocked
    assertThrows(IllegalStateException::class.java) {
      runBlocking { repo.deleteSale(trSaleId) }
    }

    // Delete settlement -> reverts deliv creditReceived=false and soft-deletes transfer sale
    repo.deleteSettlement(savedCalc.id)
    assertFalse(dao.getAllSales().first { it.id == deliv.id }.creditReceived)
    assertTrue(dao.getAllSales().first { it.id == trSaleId }.isDeleted)

    // Group-restore settlement -> restores settlement, transfer sale, and re-links delivery creditReceived=true
    restoreEngine.restoreRecycleItem(
      RecycleBinRecord(savedCalc.id, "Calculator Tab", "Settlement", "", "Rs 3200", now, "SETTLEMENT")
    )
    assertTrue(dao.getAllSales().first { it.id == deliv.id }.creditReceived)
    assertEquals(savedCalc.id, dao.getAllSales().first { it.id == deliv.id }.usedInCalcId)
    assertFalse(dao.getAllSales().first { it.id == trSaleId }.isDeleted)
  }

  @Test
  fun extendedGuards_stockTransferProductionEntityRepReturnPurchaseEditAndRecyclePreview() = runBlocking {
    val now = System.currentTimeMillis()
    // 1. Purchase lot edit: stock consumption guard & adjustSupplierPayable = false vs true
    repo.savePaymentEntity(null, "VendorPay", "03001112233", "Bank", "", "CREDIT", 0.0, "")
    val vendor = dao.getAllPaymentEntities().first { it.name == "VendorPay" && !it.isDeleted }
    repo.saveRawMaterialPurchase("Supari", now, "bag", 2.0, 50.0, 1000.0, "EXISTING", vendor, "", "")
    val supari = dao.getAllRawMaterials().first { it.name == "Supari" && !it.isDeleted }
    val lot = dao.getAllPurchases().first { it.materialId == supari.id && !it.isDeleted }
    assertEquals(2000.0, dao.getAllPaymentEntities().first { it.id == vendor.id }.currentBalance, 0.01)

    // Simulate 80 kg consumed so 20 kg remains -> editing lot down to 60 kg (short by 20 kg) must fail
    dao.upsertRawMaterial(supari.copy(stockKg = 20.0))
    assertThrows(IllegalStateException::class.java) {
      runBlocking {
        repo.saveRawMaterialPurchase("Supari", now, "bag", 1.2, 50.0, 1000.0, "EXISTING", vendor, "", "", editingLotId = lot.id, adjustSupplierPayable = true)
      }
    }
    // Restore 100 kg stock and edit lot to 3 bags @ 1200 (3600 total) keeping old payable
    dao.upsertRawMaterial(supari.copy(stockKg = 100.0))
    repo.saveRawMaterialPurchase("Supari", now, "bag", 3.0, 50.0, 1200.0, "EXISTING", vendor, "", "", editingLotId = lot.id, adjustSupplierPayable = false)
    assertEquals(2000.0, dao.getAllPaymentEntities().first { it.id == vendor.id }.currentBalance, 0.01)
    assertEquals(150.0, dao.getAllRawMaterials().first { it.id == supari.id }.stockKg, 0.01)

    // Edit again with adjustSupplierPayable = true -> supplier balance changes from 2000 - 2000 (old lot totalPayable) + 4000 = 4000
    repo.saveRawMaterialPurchase("Supari", now, "bag", 4.0, 50.0, 1000.0, "EXISTING", vendor, "", "", editingLotId = lot.id, adjustSupplierPayable = true)
    assertEquals(4000.0, dao.getAllPaymentEntities().first { it.id == vendor.id }.currentBalance, 0.01)

    // 2. Entity delete block when non-payable PAYMENT_IN reversal would drop Cash in Hand below zero
    repo.savePaymentEntity(null, "CashPartner", "03009998877", "Cash", "", "CREDIT", 0.0, "")
    val partner = dao.getAllPaymentEntities().first { it.name == "CashPartner" && !it.isDeleted }
    repo.savePaymentTransaction(null, now, "PAYMENT_IN", partner.name, 5000.0, "", "", false)
    assertNull(SarimLinkEngine.getEntityDeleteBlockReason(partner, dao.getAllPaymentTransactions(), dao.getAllSales(), dao.getAllSettlements(), dao.getAllFactoryBatches()))
    // Spend all cash so reversing the 5000 IN drops Cash in Hand negative
    repo.savePaymentTransaction(null, now, "EXPENSE", "Equipment", 5000.0, "", "", false)
    assertNotNull(SarimLinkEngine.getEntityDeleteBlockReason(partner, dao.getAllPaymentTransactions(), dao.getAllSales(), dao.getAllSettlements(), dao.getAllFactoryBatches()))

    // 3. Stock transfer, production delete, and rep return allowance guards
    repo.saveFormula(null, "MixS", 50.0, 1.0, listOf(FormulaIngredient("MATERIAL", supari.id, supari.name, 10.0)))
    val formula = dao.getAllFormulas().first { it.name == "MIXS" && !it.isDeleted }
    repo.saveStore("S1", formula.id, 300.0)
    repo.saveStore("S2", formula.id, 320.0)
    val s1 = dao.getAllStores().first { it.storeKey == "s1" }
    val s2 = dao.getAllStores().first { it.storeKey == "s2" }
    repo.saveFactoryBatch(now, formula, 5.0)
    repo.saveProductionEntry(null, now, s1, dao.getAllFormulas().first { it.id == formula.id }, 52.0, 2.0, 1.0, 100.0, emptyList())
    val prodS1 = dao.getAllProductionEntries().first { !it.isDeleted && it.storeKey == "s1" }

    // Transfer 20 kg from s1 -> s2, then sell 15 kg from s2
    val transfer = StockTransferEntity(
      id = SarimUuid.generateUUID("st", tsMs = now),
      dateEpoch = now,
      dateStr = SarimFormat.dateInput(now),
      fromStoreKey = "s1",
      fromStoreName = "S1",
      toStoreKey = "s2",
      toStoreName = "S2",
      quantityKg = 20.0,
      outDocId = SarimUuid.generateUUID("p_out", tsMs = now),
      inDocId = SarimUuid.generateUUID("p_in", tsMs = now),
    )
    dao.upsertStockTransfer(transfer)
    repo.saveSaleOrCollection(null, now, "SALE", "C_S2", "", "Direct", s2, 15.0, 100.0, "CASH", 0.0, 0.0, 0.0, false, false)

    // Deleting stock transfer must be blocked because s2 already sold 15 kg
    assertNotNull(
      SarimLinkEngine.getStockTransferDeleteBlockReason(
        transfer, dao.getAllProductionEntries(), dao.getAllSales(), dao.getAllStockTransfers(), dao.getAllSettlements()
      )
    )
    // Deleting production entry in s1 must be blocked because 20 kg was transferred out (and remaining would go negative)
    repo.saveSaleOrCollection(null, now, "SALE", "C_S1", "", "Direct", s1, 40.0, 100.0, "CASH", 0.0, 0.0, 0.0, false, false)
    assertNotNull(
      SarimLinkEngine.getProductionDeleteBlockReason(
        prodS1, dao.getAllProductionEntries(), dao.getAllSettlements(), dao.getAllStockTransfers(), dao.getAllSales()
      )
    )

    // Rep return allowance
    repo.saveCustomer(null, "RepZ", "", "", 0.0, null, "", "", false, "")
    repo.saveSaleOrCollection(null, now, "SALE", "RepZ", "", "RepZ", s1, 10.0, 100.0, "CREDIT", 0.0, 0.0, 0.0, false, false)
    val ret1 = repo.saveSaleOrCollection(null, now, "REP_RETURN", "RepZ", "", "RepZ", null, 0.0, 0.0, "REP_RETURN", 0.0, 2.0, 3.0, false, false)
    val (delivered, pendingRet, remaining) = SarimLinkEngine.getRepReturnAllowance("RepZ", null, dao.getAllSales(), dao.getAllSettlements())
    assertEquals(10.0, delivered, 0.01)
    assertEquals(5.0, pendingRet, 0.01)
    assertEquals(5.0, remaining, 0.01)
    val (_, excludingSelf, remainExcl) = SarimLinkEngine.getRepReturnAllowance("RepZ", ret1.id, dao.getAllSales(), dao.getAllSettlements())
    assertEquals(0.0, excludingSelf, 0.01)
    assertEquals(10.0, remainExcl, 0.01)

    // Preview recycle restore for batch without stock vs with stock
    val preview = restoreEngine.previewRecycleRestore(
      RecycleBinRecord(lot.id, "Inventory", "Purchase", "", "", now, "PURCHASE_LOT")
    )
    assertNull(preview.blockedReason)
    assertTrue(preview.groupSize >= 1)
  }
}
