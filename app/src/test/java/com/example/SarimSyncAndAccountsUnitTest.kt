package com.example

import com.example.data.local.CustomerEntity
import com.example.data.local.SaleTransactionEntity
import com.example.data.model.AccountSessionInfo
import com.example.data.model.ConflictRecordSnapshot
import com.example.data.model.MainTab
import com.example.data.model.RolesHelper
import com.example.data.model.SarimUuid
import com.example.data.model.UserRole
import com.example.data.remote.DbQuotaStats
import com.example.data.remote.InMemoryKvStorePersistence
import com.example.data.remote.SarimWebPullMappers
import com.example.data.remote.SarimWebSchema
import com.example.data.remote.UuidSyncRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SarimSyncAndAccountsUnitTest {

  @Test
  fun rolesHelper_cleansNormalizesAndDerivesSessionTabs() {
    assertEquals(
      listOf("admin", "rep", "sales"),
      RolesHelper.cleanRoles(listOf("SALES", "rep", "unknown", "Admin", "rep")),
    )
    assertEquals(
      listOf("rep", "payments"),
      RolesHelper.normalizeRoles(mapOf("roles" to listOf("payments", "rep"))),
    )
    assertEquals(
      listOf("factory"),
      RolesHelper.normalizeRoles(mapOf("role" to "FACTORY")),
    )

    val adminSession = RolesHelper.deriveSession(listOf("admin", "rep"))
    assertEquals("admin", adminSession.mode)
    assertEquals(MainTab.entries, adminSession.tabs)
    assertFalse(RolesHelper.rolesRestrictMoney(listOf("admin", "sales")))

    val salesSession = RolesHelper.deriveSession(listOf("sales", "production"))
    assertEquals("userrole", salesSession.mode)
    assertEquals(listOf(MainTab.PRODUCTION, MainTab.SALES), salesSession.tabs)
    assertTrue(RolesHelper.rolesRestrictMoney(listOf("sales", "production")))

    val prodOnlySession = RolesHelper.deriveSession(listOf("production"))
    assertEquals(listOf(MainTab.PRODUCTION), prodOnlySession.tabs)
    assertFalse(RolesHelper.rolesRestrictMoney(listOf("production")))
  }

  @Test
  fun rolesHelper_workspaceOwnershipAdoptionAndLegacyRulesMatchWeb() {
    val ownerDoc = mapOf<String, Any?>("roles" to listOf("admin"))
    assertNull(RolesHelper.resolveOwnerUid(ownerDoc, "owner_1"))
    assertFalse(RolesHelper.isLegacyAccount(ownerDoc, "owner_1"))

    val legacyEmptyDoc = mapOf<String, Any?>()
    assertTrue(RolesHelper.isLegacyAccount(legacyEmptyDoc, "owner_1"))
    assertEquals(setOf(UserRole.ADMIN), RolesHelper.toUserRoleSet(emptyList(), legacy = true))

    val memberDoc = mapOf<String, Any?>("ownerUid" to "owner_1", "roles" to listOf("rep"))
    assertEquals("owner_1", RolesHelper.resolveOwnerUid(memberDoc, "member_2"))
    assertFalse(RolesHelper.isLegacyAccount(memberDoc, "member_2"))

    assertEquals(true to null, RolesHelper.canAdoptAccount(null, "u2", "owner_1", emptySet()))
    assertEquals(true to null, RolesHelper.canAdoptAccount(memberDoc, "member_2", "owner_1", emptySet()))
    assertEquals(false to "external", RolesHelper.canAdoptAccount(memberDoc, "member_2", "other_ws", emptySet()))
    assertEquals(
      true to null,
      RolesHelper.canAdoptAccount(mapOf("email" to "x@y.com"), "u_known", "owner_1", setOf("u_known")),
    )
  }

  @Test
  fun rolesHelper_repRemovalPlanDetectsExistingRecordsAndPreservesOthers() {
    val reps = listOf("Farman", "Zubair")
    val emptyPlan = RolesHelper.repRemovalPlan(
      name = "Farman",
      repsList = reps,
      sales = emptyList(),
      customers = emptyList(),
      transactions = emptyList(),
      expenses = emptyList(),
      returns = emptyList(),
    )
    assertFalse(emptyPlan.blocked)
    assertEquals(0, emptyPlan.count)
    assertTrue(emptyPlan.remove)

    val sale = SaleTransactionEntity(
      id = "s1",
      dateEpoch = 1_700_000_000_000L,
      mode = "SALE",
      customerId = "c1",
      customerName = "Ali",
      storeKey = "main",
      storeName = "MAIN",
      quantityKg = 10.0,
      pricePerKg = 300.0,
      totalValue = 3000.0,
      totalCost = 2000.0,
      profit = 1000.0,
      paymentType = "CASH",
      repName = "Farman",
    )
    val cust = CustomerEntity(
      id = "c1",
      name = "Ali",
      isRepCustomer = true,
      repName = "Farman",
    )
    val blockedPlan = RolesHelper.repRemovalPlan(
      name = "farman",
      repsList = reps,
      sales = listOf(sale),
      customers = listOf(cust),
      transactions = emptyList(),
      expenses = emptyList(),
      returns = emptyList(),
    )
    assertTrue(blockedPlan.blocked)
    assertEquals(2, blockedPlan.count)
    assertFalse(blockedPlan.remove)

    assertEquals(listOf("Zubair"), RolesHelper.withoutRep(reps, "FARMAN"))
  }

  @Test
  fun accountSessionInfo_roundTripsThroughJson() {
    val session = AccountSessionInfo(
      uid = "uid_123",
      email = "rep@example.com",
      name = "Farman Khan",
      roles = listOf("rep", "sales"),
      ownerUid = "ws_owner",
      legacy = false,
      approved = true,
      ts = 1_750_000_000_000L,
    )
    val decoded = AccountSessionInfo.fromJson(session.toJson())
    assertNotNull(decoded)
    assertEquals(session, decoded)
  }

  @Test
  fun sarimWebSchema_buildsValidPendingDocsAndMapsBackToApprovalAndAccount() {
    assertEquals("rep__doc_1", SarimWebSchema.pendingId("rep", "doc/1"))
    assertTrue(SarimWebSchema.isGatedCollection(SarimWebSchema.COL_SALES))
    assertFalse(SarimWebSchema.isGatedCollection(SarimWebSchema.COL_STORES))

    val pendingMap = SarimWebSchema.buildPendingDocMap(
      collection = SarimWebSchema.COL_SALES,
      docId = "sale_99",
      action = "set",
      data = mapOf("customerName" to "Noor Azam", "totalValue" to 12500.0),
      whoUid = "u_staff",
      whoName = "Staff User",
      whoEmail = "staff@example.com",
      nowMs = 1_750_000_000_000L,
      isEdit = false,
    )
    assertEquals("sales__sale_99", pendingMap["id"])
    assertEquals("pending", pendingMap["status"])

    val approval = SarimWebPullMappers.mapToApproval("sales__sale_99", pendingMap)
    assertNotNull(approval)
    approval!!
    assertEquals("Staff User", approval.submittedBy)
    assertEquals("u_staff", approval.submittedByUid)
    assertEquals("staff@example.com", approval.submittedByEmail)
    assertEquals("Sales", approval.category)
    assertEquals("New", approval.actionKind)
    assertEquals("PENDING", approval.status)
    assertEquals("sales", approval.targetCollection)
    assertEquals("sale_99", approval.docId)
    assertEquals(12500.0, approval.amount, 0.01)

    val account = SarimWebPullMappers.mapToAppAccount(
      uid = "u_staff",
      m = mapOf(
        "email" to "Staff@Example.com",
        "displayName" to "Staff User",
        "roles" to listOf("sales", "rep"),
        "approved" to false,
        "ownerUid" to "ws_owner",
        "createdBy" to "owner@example.com",
      ),
    )
    assertEquals("u_staff", account.uid)
    assertEquals("staff@example.com", account.email)
    assertEquals("Staff User", account.name)
    assertEquals("REP,SALES", account.rolesCsv)
    assertFalse(account.approved)
    assertEquals("ws_owner", account.ownerUid)
    assertEquals("owner@example.com", account.createdBy)
  }

  @Test
  fun sarimUuid_generatesValidEnrichedIdsExtractsMetadataAndValidatesDeviceIds() {
    SarimUuid.clearDeviceState()
    val fixedNow = 1_760_000_123_456L
    val devId = SarimUuid.newDeviceId(fixedNow)
    assertTrue(SarimUuid.isValidDeviceId(devId, loginTs = fixedNow - 10_000L))
    assertFalse(SarimUuid.isValidDeviceId(devId, loginTs = fixedNow + 120_000L))
    assertFalse(SarimUuid.isValidDeviceId("legacy-device-id", loginTs = fixedNow))

    val shard = SarimUuid.initDeviceShard(devId)
    assertEquals(SarimUuid.deriveDeviceShard(devId), shard)
    assertEquals(4, shard.length)

    SarimUuid.currentAppMode = SarimUuid.deriveAppMode(listOf("rep"))
    assertEquals(SarimUuid.MODE_REP, SarimUuid.currentAppMode)

    val saleId = SarimUuid.generateUUID(SarimUuid.PREFIX_SALE, tsMs = fixedNow)
    assertTrue(saleId.startsWith("sale-"))
    assertTrue(SarimUuid.validateUUID(saleId))
    assertFalse(SarimUuid.validateUUID("old_debt_1234"))
    assertFalse(SarimUuid.validateUUID("exp_555"))

    val meta = SarimUuid.extractUUIDMeta(saleId)
    assertNotNull(meta)
    meta!!
    assertEquals(shard, meta.deviceShard)
    assertEquals(SarimUuid.MODE_REP, meta.appMode)
    assertTrue(meta.timestampMs >= fixedNow)
    assertTrue(meta.sequence in 0..255)
    assertEquals(8, meta.v5entropy.length)

    // Monotonic tie-breaker when timestamps equal
    val secondId = SarimUuid.generateUUID(SarimUuid.PREFIX_SALE, tsMs = fixedNow)
    assertNotEquals(saleId, secondId)
    val cmp = SarimUuid.compareRecordVersions(
      idA = secondId, updatedAtA = 1000L, timestampA = 1000L, createdAtA = 1000L,
      idB = saleId, updatedAtB = 1000L, timestampB = 1000L, createdAtB = 1000L,
    )
    assertTrue(cmp > 0)
    SarimUuid.clearDeviceState()
  }

  @Test
  fun sarimUuid_semanticConflictGuardsProtectLocalData() {
    // 1. Pending syncPending always keeps local
    assertTrue(
      SarimUuid.shouldLocalWinConflict(
        collection = "sales",
        local = ConflictRecordSnapshot(id = "a", updatedAt = 100L, syncPending = true),
        remote = ConflictRecordSnapshot(id = "a", updatedAt = 999L),
      )
    )

    // 2. Calculator protects non-zero local financial totals against zeroed remote
    assertTrue(
      SarimUuid.shouldLocalWinConflict(
        collection = "calculator",
        local = ConflictRecordSnapshot(id = "c1", updatedAt = 100L, totalSoldKg = 25.0, grossRevenue = 7500.0),
        remote = ConflictRecordSnapshot(id = "c1", updatedAt = 900L, totalSoldKg = 0.0, grossRevenue = 0.0),
      )
    )
    assertFalse(
      SarimUuid.shouldLocalWinConflict(
        collection = "calculator",
        local = ConflictRecordSnapshot(id = "c1", updatedAt = 100L, totalSoldKg = 25.0, grossRevenue = 7500.0),
        remote = ConflictRecordSnapshot(id = "c1", updatedAt = 900L, totalSoldKg = 30.0, grossRevenue = 9000.0),
      )
    )

    // 3. Production guards isReturn & formula cost/units
    assertTrue(
      SarimUuid.shouldLocalWinConflict(
        collection = "production",
        local = ConflictRecordSnapshot(id = "p1", updatedAt = 100L, isReturn = true),
        remote = ConflictRecordSnapshot(id = "p1", updatedAt = 500L, isReturn = false),
      )
    )
    assertTrue(
      SarimUuid.shouldLocalWinConflict(
        collection = "production",
        local = ConflictRecordSnapshot(id = "p2", updatedAt = 100L, formulaUnitsUsed = 2.0, totalFormulaCost = 400.0),
        remote = ConflictRecordSnapshot(id = "p2", updatedAt = 500L, formulaUnitsUsed = 0.0, totalFormulaCost = 0.0),
      )
    )

    // 4. Inventory guards supplierId & paid status
    assertTrue(
      SarimUuid.shouldLocalWinConflict(
        collection = "inventory",
        local = ConflictRecordSnapshot(id = "i1", updatedAt = 100L, supplierId = "supp-1", paymentStatus = "PAID"),
        remote = ConflictRecordSnapshot(id = "i1", updatedAt = 500L, supplierId = "", paymentStatus = "UNPAID"),
      )
    )
  }

  @Test
  fun uuidSyncRegistry_tracksSkipRulesQuota24hResetAndPersistence() = runBlocking {
    val kv = InMemoryKvStorePersistence()
    val registry = UuidSyncRegistry(kv)
    val t0 = 1_760_000_000_000L

    val devId = registry.loadOrInitDeviceId(loginTs = t0 - 1_000L, nowMs = t0)
    assertTrue(SarimUuid.isValidDeviceId(devId))
    assertEquals(SarimUuid.deriveDeviceShard(devId), registry.getDeviceShard())

    val localId = SarimUuid.generateUUID(SarimUuid.PREFIX_PROD, tsMs = t0)
    assertTrue(registry.isLocalOrigin(localId))
    assertTrue(registry.skipDownload(SarimWebSchema.COL_PRODUCTION, localId))

    // Dirty local-origin doc should NOT skip download or upload
    registry.markLocalModified(SarimWebSchema.COL_PRODUCTION, localId, nowMs = t0)
    assertFalse(registry.skipUpload(SarimWebSchema.COL_PRODUCTION, localId))
    assertFalse(registry.skipDownload(SarimWebSchema.COL_PRODUCTION, localId))

    // Marking uploaded clears dirty and causes skipUpload
    registry.markUploaded(SarimWebSchema.COL_PRODUCTION, localId, nowMs = t0 + 10L)
    assertFalse(registry.isDirtyId(SarimWebSchema.COL_PRODUCTION, localId))
    assertTrue(registry.skipUpload(SarimWebSchema.COL_PRODUCTION, localId))

    // Downloaded remote ID skips future download unless newDeviceRestore is enabled
    val remoteId = "sale-01900000-0000-4120-8000-deadbeefcafe"
    assertTrue(registry.shouldApplyCloud(SarimWebSchema.COL_SALES, remoteId))
    registry.markDownloaded(SarimWebSchema.COL_SALES, remoteId, nowMs = t0 + 20L)
    assertTrue(registry.skipDownload(SarimWebSchema.COL_SALES, remoteId))
    assertFalse(registry.shouldApplyCloud(SarimWebSchema.COL_SALES, remoteId))

    registry.setNewDeviceRestore(true)
    assertTrue(registry.shouldApplyCloud(SarimWebSchema.COL_SALES, remoteId))
    registry.setNewDeviceRestore(false)

    // Quota tracking and 24h auto-reset
    registry.trackFirestoreRead(count = 12, nowMs = t0)
    val qAfterWrite = registry.trackFirestoreWrite(count = 5, nowMs = t0 + 100L)
    assertEquals(12, qAfterWrite.reads)
    assertEquals(5, qAfterWrite.writes)

    val qReset = registry.getDbStats(nowMs = t0 + DbQuotaStats.RESET_INTERVAL_MS + 1L)
    assertEquals(0, qReset.reads)
    assertEquals(0, qReset.writes)

    // Reloading fresh instance from same persistence retains uploaded/downloaded/deltastats
    registry.updateSyncStats(SarimWebSchema.COL_SALES, reads = 4, writes = 2, nowMs = t0 + 200L)
    val reloaded = UuidSyncRegistry(kv)
    reloaded.loadAll()
    assertTrue(reloaded.wasUploaded(SarimWebSchema.COL_PRODUCTION, localId))
    assertTrue(reloaded.wasDownloaded(SarimWebSchema.COL_SALES, remoteId))
    assertEquals(1, reloaded.getCollectionDeltaStats(SarimWebSchema.COL_SALES).syncCount)

    reloaded.clearAll()
    assertFalse(reloaded.wasUploaded(SarimWebSchema.COL_PRODUCTION, localId))
    SarimUuid.clearDeviceState()
  }
}
