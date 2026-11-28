package com.example.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.local.PreferencesManager
import com.example.data.local.RoomKvStorePersistence
import com.example.data.local.SafeSqliteCacheReader
import com.example.data.local.SarimDatabase
import com.example.data.model.RolesHelper
import com.example.data.model.UserRole
import com.example.data.remote.FirebaseSyncManager
import com.example.data.remote.UuidSyncRegistry
import com.example.data.repository.SarimRepository
import com.example.data.repository.SarimSyncAndBackupRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** True while the UI is visible; the in-app auto-sync handles syncing then, so the worker stays out of its way. */
object AppForeground {
  val visible = AtomicBoolean(false)
}

/**
 * Android port of the web service worker's `offline-queue-sync` background sync: when the app is closed and
 * a connection is available, upload pending local changes using the saved sign-in (no UI, no dialogs).
 */
class PendingSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    if (AppForeground.visible.get()) return Result.success()
    val app = applicationContext
    return try {
      val syncManager = FirebaseSyncManager(app)
      val user = syncManager.currentUser() ?: return Result.success() // signed out: nothing to upload
      val prefsManager = PreferencesManager(app)
      val prefs = prefsManager.prefsFlow.first()
      if (prefs.offlineModeActive) return Result.success()

      val dao = SarimDatabase.getInstance(app).dao()
      val safeReader = SafeSqliteCacheReader(dao)
      val uuidRegistry = UuidSyncRegistry(RoomKvStorePersistence(dao, safeReader))
      val syncRepo = SarimSyncAndBackupRepository(dao, syncManager, prefsManager, safeReader, uuidRegistry)

      val pending = syncRepo.countPendingSync()
      if (pending <= 0) return Result.success()

      val session = prefsManager.readCachedAccountSession()
      val roles = session?.let { com.example.data.model.RolesHelper.toUserRoleSet(it.roles, it.legacy) } ?: setOf(UserRole.ADMIN)
      val isAdmin = UserRole.ADMIN in roles
      val isGated = !isAdmin && user.uid.isNotBlank() && !session?.ownerUid.isNullOrBlank()

      val result = syncRepo.syncWithFirestore(isGated = isGated, session = session)
      if (result.isSuccess) {
        runCatching { SarimRepository(dao, safeReader, uuidRegistry).syncLedgerFromTabs() }
        Result.success()
      } else if (runAttemptCount < MAX_ATTEMPTS) {
        Result.retry()
      } else {
        Result.success() // the next periodic run / app open tries again
      }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }
  }

  companion object {
    private const val MAX_ATTEMPTS = 5
    private const val ONE_TIME = "sarim_pending_sync_now"
    private const val PERIODIC = "sarim_pending_sync_periodic"

    private val needsNetwork = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Safety net: every 15 minutes while online (WorkManager's minimum), survives app close and reboot. */
    fun schedulePeriodic(context: Context) {
      val req = PeriodicWorkRequestBuilder<PendingSyncWorker>(15, TimeUnit.MINUTES)
        .setConstraints(needsNetwork)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        .build()
      WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Runs as soon as a connection exists; call when the app leaves the screen with changes still pending. */
    fun enqueueNow(context: Context) {
      val req = OneTimeWorkRequestBuilder<PendingSyncWorker>()
        .setConstraints(needsNetwork)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
      WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancelAll(context: Context) {
      val wm = WorkManager.getInstance(context)
      wm.cancelUniqueWork(ONE_TIME)
      wm.cancelUniqueWork(PERIODIC)
    }
  }
}
