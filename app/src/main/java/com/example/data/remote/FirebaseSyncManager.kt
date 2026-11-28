package com.example.data.remote

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.data.local.AppAccountEntity
import com.example.data.local.ApprovalRequestEntity
import com.example.data.local.DeletionTombstoneEntity
import com.example.data.local.NdappOutboxEntity
import com.example.data.model.AccountSessionInfo
import com.example.data.model.RolesHelper
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

enum class OperationType(val value: String) {
  CREATE("create"),
  UPDATE("update"),
  DELETE("delete"),
  LIST("list"),
  GET("get"),
  WRITE("write"),
}

sealed interface SessionCheckResult {
  data class Allowed(val session: AccountSessionInfo) : SessionCheckResult
  data class Denied(val reason: String) : SessionCheckResult
  data object OfflineFallback : SessionCheckResult
}

sealed interface AdminAccountPrepareResult {
  data class Ready(val uid: String, val email: String, val name: String, val roles: List<String>) : AdminAccountPrepareResult
  data class NeedsAdoptConfirm(val uid: String, val email: String, val name: String, val roles: List<String>) : AdminAccountPrepareResult
  data class Blocked(val title: String, val message: String) : AdminAccountPrepareResult
}

data class PendingSubmitItem(
  val collection: String,
  val docId: String,
  val action: String, // "set" | "delete"
  val data: Map<String, Any?>?,
  val isEdit: Boolean = false,
)

data class LiveConfigDocCloudState(
  val exists: Boolean,
  val presentFields: Set<String> = emptySet(),
  val fieldPreviews: Map<String, String> = emptyMap(),
)

data class LiveFirestoreDiagnostics(
  val collectionCounts: Map<String, Int>,
  val configDocs: Map<String, LiveConfigDocCloudState>,
  val fetchedAt: Long = System.currentTimeMillis(),
)

interface RealtimeSyncCallbacks {
  fun getSessionStartMillis(): Long
  fun isCurrentlyOnline(): Boolean
  fun onForceSignOut(reason: String)
  fun onSessionDocChanged(info: AccountSessionInfo)
  fun onRemotePing(collections: List<String>)
  fun onAdminPendingSnapshot(items: List<ApprovalRequestEntity>)
  fun onOwnPendingResolved(item: ApprovalRequestEntity)
  fun onSettingsConfigDoc(doc: Map<String, Any>)
  fun onTeamDoc(doc: Map<String, Any>)
  fun onYearCloseSignal(doc: Map<String, Any>)
  fun onStoresDoc(doc: Map<String, Any>)
  fun onFormulasDoc(doc: Map<String, Any>)
  fun onDailyLedgerDoc(doc: Map<String, Any>)
  fun onCategoryDoc(colKey: String, doc: Map<String, Any>)
  fun onDeletionsSnapshot(upserts: List<Pair<String, Map<String, Any>>>, removedIds: List<String>)
  fun onPhotosSnapshot(upserts: List<Pair<String, Map<String, Any>>>)
  fun onRecordCollectionSnapshot(
    collection: String,
    docs: List<Pair<String, Map<String, Any>>>,
    removedIds: List<String>,
  )
}

fun handleFirestoreError(exception: Throwable, operationType: OperationType, path: String?): String {
  val causes = generateSequence(exception) { it.cause }
  val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
    ?: causes.firstOrNull { it !is CancellationException }
    ?: exception

  val currentUser = runCatching { Firebase.auth }.getOrNull()?.currentUser

  val providerInfoList = currentUser?.providerData?.map { provider ->
    JSONObject().apply {
      put("providerId", provider.providerId)
      put("email", provider.email)
    }
  } ?: emptyList()

  val authInfoJson = JSONObject().apply {
    put("userId", currentUser?.uid)
    put("email", currentUser?.email)
    put("emailVerified", currentUser?.isEmailVerified)
    put("tenantId", currentUser?.tenantId)
    put("providerInfo", JSONArray(providerInfoList))
  }

  val errorInfoJson = JSONObject().apply {
    put("error", rootError.message ?: rootError.toString())
    put("operationType", operationType.value)
    put("path", path)
    put("authInfo", authInfoJson)
  }

  val jsonString = errorInfoJson.toString()
  Log.e("FirestoreError", "Firestore Error: $jsonString")
  return jsonString
}

fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
  val listener = FirebaseAuth.AuthStateListener { auth -> trySend(auth.currentUser) }
  addAuthStateListener(listener)
  awaitClose { removeAuthStateListener(listener) }
}

class FirebaseSyncManager(
  private val appContext: Context? = null,
  db: FirebaseFirestore? = null,
  auth: FirebaseAuth? = null,
) {
  private val _db: FirebaseFirestore? = db ?: runCatching {
    if (appContext == null || FirebaseApp.getApps(appContext).isNotEmpty()) {
      FirebaseFirestore.getInstance()
    } else {
      null
    }
  }.getOrNull()

  private val _auth: FirebaseAuth? = auth ?: runCatching {
    if (appContext == null || FirebaseApp.getApps(appContext).isNotEmpty()) {
      Firebase.auth
    } else {
      null
    }
  }.getOrNull()

  val isFirebaseConfigured: Boolean
    get() = _db != null && _auth != null

  private val db: FirebaseFirestore
    get() = _db ?: throw IllegalStateException("Firebase is not configured (missing google-services.json).")

  private val auth: FirebaseAuth
    get() = _auth ?: throw IllegalStateException("Firebase is not configured (missing google-services.json).")

  @Volatile private var cachedWorkspaceUid: Pair<String, String>? = null
  @Volatile private var lastEmitPingAt: Long = 0L
  @Volatile private var lastSeenPingTs: Long = 0L
  @Volatile var onQuotaEvent: ((reads: Int, writes: Int) -> Unit)? = null
  private val activeListeners = mutableListOf<ListenerRegistration>()
  private val listenerLock = Any()

  private fun recordQuota(reads: Int = 0, writes: Int = 0) {
    if (reads > 0 || writes > 0) {
      onQuotaEvent?.invoke(reads, writes)
    }
  }

  fun currentUser(): FirebaseUser? = _auth?.currentUser

  fun authStateFlow(): Flow<FirebaseUser?> = _auth?.authStateFlow() ?: flowOf(null)

  private fun resolveWebClientId(context: Context): String? {
    val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
    if (resId == 0) return null
    return runCatching { context.getString(resId) }.getOrNull()?.takeIf { it.isNotBlank() }
  }

  private fun requireUserId(): String {
    val uid = _auth?.currentUser?.uid
    if (uid.isNullOrBlank()) {
      throw IllegalStateException("User must be signed in before syncing with Firestore.")
    }
    return uid
  }

  fun clearCachedWorkspace() {
    cachedWorkspaceUid = null
  }

  /**
   * Mirrors `dataRootUid()` from web repo `modules/business.js`.
   */
  suspend fun resolveWorkspaceRootUid(): String {
    val uid = requireUserId()
    cachedWorkspaceUid?.let { (forUid, ownerUid) ->
      if (forUid == uid) return ownerUid
    }
    val resolved = try {
      val userDoc = db.collection("users").document(uid).get().await()
      val owner = userDoc.getString("ownerUid")?.trim()
      if (!owner.isNullOrBlank() && owner != uid) owner else uid
    } catch (_: Exception) {
      uid
    }
    cachedWorkspaceUid = uid to resolved
    return resolved
  }

  /**
   * Mirrors `_checkUserApproved()` + `_applyGoogleUser()` / `syncAccountSession()` from web `modules/sync.js`
   * and `modules/account-session.js`.
   */
  suspend fun checkUserApprovedAndLoadSession(
    user: FirebaseUser,
    isOnline: Boolean,
  ): SessionCheckResult {
    val uid = user.uid
    val email = (user.email ?: "").trim().lowercase()
    if (!isOnline) {
      return SessionCheckResult.OfflineFallback
    }
    return try {
      val snap = withTimeoutOrNull(8_000L) {
        db.collection("users").document(uid).get().await()
      } ?: return SessionCheckResult.OfflineFallback

      if (!snap.exists()) {
        runCatching { auth.signOut() }
        cachedWorkspaceUid = null
        return SessionCheckResult.Denied(
          "Access denied. Your account ($email) is not registered in this system. Contact the administrator."
        )
      }
      val data = snap.data ?: emptyMap()
      if (data["approved"] == false) {
        runCatching { auth.signOut() }
        cachedWorkspaceUid = null
        return SessionCheckResult.Denied(
          "Access denied. Your account has been suspended. Contact the administrator."
        )
      }
      val info = RolesHelper.accountInfoFromDoc(
        data = data,
        uid = uid,
        email = email,
        fallbackDisplayName = user.displayName ?: "",
      )
      cachedWorkspaceUid = uid to (info.ownerUid?.takeIf { it.isNotBlank() } ?: uid)

      // Update authProviders + lastLoginAt on self doc (permitted by Firestore rules lines 70-73)
      runCatching {
        val providers = user.providerData.mapNotNull { it.providerId.takeIf { pid -> pid.isNotBlank() } }
        db.collection("users").document(uid).set(
          mapOf(
            "authProviders" to providers,
            "lastLoginAt" to System.currentTimeMillis(),
          ),
          SetOptions.merge(),
        ).await()
      }
      SessionCheckResult.Allowed(info)
    } catch (e: CancellationException) {
      throw e
    } catch (e: FirebaseFirestoreException) {
      if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
        runCatching { auth.signOut() }
        cachedWorkspaceUid = null
        SessionCheckResult.Denied("Access denied. You do not have permission to use this app.")
      } else {
        SessionCheckResult.OfflineFallback
      }
    } catch (_: Exception) {
      SessionCheckResult.OfflineFallback
    }
  }

  // =====================================================================
  // Google Credential Manager & Email/Password Auth
  // =====================================================================

  fun attemptAutoSignIn(
    context: Context,
    credentialManager: CredentialManager,
    scope: CoroutineScope,
    onAuthSuccess: () -> Unit,
    onUnauthenticated: () -> Unit,
  ) {
    val activeAuth = _auth
    if (activeAuth == null) {
      onUnauthenticated()
      return
    }
    if (activeAuth.currentUser != null) {
      onAuthSuccess()
      return
    }
    val clientId = resolveWebClientId(context)
    if (clientId == null) {
      onUnauthenticated()
      return
    }
    val googleIdOption = GetGoogleIdOption.Builder()
      .setFilterByAuthorizedAccounts(true)
      .setServerClientId(clientId)
      .setAutoSelectEnabled(true)
      .build()
    val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()
    scope.launch {
      try {
        val cred = credentialManager.getCredential(context, request).credential
        if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
          val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
          auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
          cachedWorkspaceUid = null
          onAuthSuccess()
        } else {
          onUnauthenticated()
        }
      } catch (_: Exception) {
        onUnauthenticated()
      }
    }
  }

  fun onGoogleSignInClicked(
    context: Context,
    credentialManager: CredentialManager,
    scope: CoroutineScope,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    onAuthCancelled: () -> Unit = {},
  ) {
    if (!isFirebaseConfigured) {
      onAuthError("Firebase is not configured. Add app/google-services.json or continue in Offline Mode.")
      return
    }
    val clientId = resolveWebClientId(context)
    if (clientId == null) {
      onAuthError("Google Sign-In configuration missing: default_web_client_id not found")
      return
    }
    val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()
    scope.launch {
      try {
        val activity = context as? Activity
          ?: throw IllegalStateException("Activity context required for Google Sign-In")
        val cred = credentialManager.getCredential(activity, request).credential
        if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
          val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
          auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
          cachedWorkspaceUid = null
          onAuthSuccess()
        } else {
          onAuthError("Unexpected credential type")
        }
      } catch (e: GetCredentialCancellationException) {
        Log.w("Auth", "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
        onAuthCancelled()
      } catch (e: Exception) {
        Log.e("Auth", "Google Sign-In failed", e)
        onAuthError(e.localizedMessage ?: "Sign in failed")
      }
    }
  }

  fun signOut(
    credentialManager: CredentialManager,
    scope: CoroutineScope,
    onSignOutComplete: () -> Unit = {},
  ) {
    stopRealtimeListeners()
    scope.launch {
      withContext(NonCancellable) {
        try {
          credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
          Log.w("Auth", "Failed to clear credential state: ${e.message}")
        } finally {
          cachedWorkspaceUid = null
          runCatching { _auth?.signOut() }
          onSignOutComplete()
        }
      }
    }
  }

  suspend fun signInWithEmailPassword(email: String, password: String): Result<FirebaseUser> {
    if (!isFirebaseConfigured) {
      return Result.failure(IllegalStateException("Firebase is not configured. Add app/google-services.json or continue in Offline Mode."))
    }
    return try {
      val res = auth.signInWithEmailAndPassword(email.trim(), password).await()
      val user = res.user ?: return Result.failure(IllegalStateException("Sign-in did not return a user account."))
      cachedWorkspaceUid = null
      Result.success(user)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Result.failure(IllegalStateException(mapAuthError(e)))
    }
  }

  suspend fun verifyCurrentUserPassword(password: String): Result<Unit> {
    if (!isFirebaseConfigured) return Result.success(Unit)
    val user = _auth?.currentUser ?: return Result.success(Unit)
    val usesPassword = user.providerData.any { it.providerId == EmailAuthProvider.PROVIDER_ID }
    val email = user.email?.takeIf { it.isNotBlank() }
    if (!usesPassword || email == null) return Result.success(Unit)
    return try {
      val cred = EmailAuthProvider.getCredential(email, password)
      user.reauthenticate(cred).await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Result.failure(IllegalStateException(mapAuthError(e)))
    }
  }

  suspend fun registerWithEmailPassword(
    displayName: String,
    email: String,
    password: String,
  ): Result<FirebaseUser> {
    if (!isFirebaseConfigured) {
      return Result.failure(IllegalStateException("Firebase is not configured. Use Offline Mode or add google-services.json."))
    }
    return try {
      val res = auth.createUserWithEmailAndPassword(email.trim(), password).await()
      val user = res.user ?: return Result.failure(IllegalStateException("Registration did not return a user account."))
      val cleanName = displayName.trim()
      if (cleanName.isNotEmpty()) {
        runCatching {
          val profileUpdates = UserProfileChangeRequest.Builder()
            .setDisplayName(cleanName)
            .build()
          user.updateProfile(profileUpdates).await()
        }
      }
      cachedWorkspaceUid = null
      Result.success(user)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Result.failure(IllegalStateException(mapAuthError(e)))
    }
  }

  suspend fun sendPasswordReset(email: String): Result<Unit> {
    if (!isFirebaseConfigured) {
      return Result.failure(IllegalStateException("Firebase is not configured. Password reset requires cloud auth."))
    }
    return try {
      auth.sendPasswordResetEmail(email.trim()).await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Result.failure(IllegalStateException(mapAuthError(e)))
    }
  }

  private fun mapAuthError(e: Exception): String {
    val authEx = e as? FirebaseAuthException
    return when (authEx?.errorCode) {
      "ERROR_INVALID_EMAIL" -> "Please enter a valid email address."
      "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL" -> "Incorrect email or password."
      "ERROR_USER_NOT_FOUND" -> "No dealer account found for that email."
      "ERROR_EMAIL_ALREADY_IN_USE" -> "An account already exists with this email. Switch to Sign In."
      "ERROR_WEAK_PASSWORD" -> "Password must be at least 6 characters."
      "ERROR_USER_DISABLED" -> "This dealer account has been disabled."
      "ERROR_OPERATION_NOT_ALLOWED" -> "Email/Password sign-in is disabled in Firebase Console for this project."
      "ERROR_TOO_MANY_REQUESTS" -> "Too many attempts. Please wait a moment and try again."
      else -> e.localizedMessage ?: "Authentication failed."
    }
  }

  // =====================================================================
  // App Accounts Management (Secondary FirebaseApp _adminCreate + /users + settings/accounts)
  // =====================================================================

  private fun getOrCreateSecondaryAuth(): FirebaseAuth {
    val ctx = appContext
      ?: throw IllegalStateException("Application context required to manage accounts.")
    val existing = FirebaseApp.getApps(ctx).firstOrNull { it.name == "_adminCreate" }
    val secondaryApp = existing ?: run {
      val opts = FirebaseOptions.fromResource(ctx) ?: FirebaseApp.getInstance().options
      FirebaseApp.initializeApp(ctx, opts, "_adminCreate")
    }
    return FirebaseAuth.getInstance(secondaryApp)
  }

  private suspend fun readAccountsIndex(wsUid: String): MutableList<Map<String, Any?>> {
    return try {
      val snap = db.collection("users").document(wsUid)
        .collection(SarimWebSchema.COL_SETTINGS).document("accounts")
        .get().await()
      val raw = snap.data?.get("accounts") as? List<*> ?: return mutableListOf()
      raw.mapNotNull { item ->
        @Suppress("UNCHECKED_CAST")
        (item as? Map<String, Any?>)?.toMap()
      }.toMutableList()
    } catch (_: Exception) {
      mutableListOf()
    }
  }

  private suspend fun writeAccountsIndex(wsUid: String, entries: List<Map<String, Any?>>) {
    db.collection("users").document(wsUid)
      .collection(SarimWebSchema.COL_SETTINGS).document("accounts")
      .set(
        mapOf(
          "accounts" to entries,
          "updatedAt" to System.currentTimeMillis(),
        ),
        SetOptions.merge(),
      ).await()
  }

  private suspend fun patchAccountsIndex(wsUid: String, uid: String, entry: Map<String, Any?>?) {
    val list = readAccountsIndex(wsUid).filter { it["uid"]?.toString() != uid }.toMutableList()
    if (entry != null) list.add(entry)
    writeAccountsIndex(wsUid, list)
  }

  suspend fun loadWorkspaceAccounts(wsUid: String): Result<List<AppAccountEntity>> {
    if (!isFirebaseConfigured) {
      return Result.failure(IllegalStateException("Firebase is not configured."))
    }
    return try {
      val selfUid = requireUserId()
      val usersCol = db.collection("users")
      val ownerSnap = runCatching { usersCol.document(wsUid).get().await() }.getOrNull()
      val meSnap = runCatching { usersCol.document(selfUid).get().await() }.getOrNull()
      val indexed = readAccountsIndex(wsUid)
      val ownedSnap = runCatching { usersCol.whereEqualTo("ownerUid", wsUid).get().await() }.getOrNull()

      val byUid = linkedMapOf<String, AppAccountEntity>()
      indexed.forEach { a ->
        val u = a["uid"]?.toString()?.trim().orEmpty()
        if (u.isNotBlank()) {
          byUid[u] = SarimWebPullMappers.mapToAppAccount(u, a).copy(approved = a["approved"] != false)
        }
      }
      val live = hashSetOf<String>()
      fun take(docSnap: com.google.firebase.firestore.DocumentSnapshot?) {
        if (docSnap != null && docSnap.exists()) {
          val d = docSnap.data ?: emptyMap()
          byUid[docSnap.id] = SarimWebPullMappers.mapToAppAccount(docSnap.id, d)
          live.add(docSnap.id)
        }
      }
      take(ownerSnap)
      take(meSnap)
      ownedSnap?.documents?.forEach { take(it) }

      val staleUids = byUid.keys.filter { it !in live }
      staleUids.forEach { staleUid ->
        val snap = runCatching { usersCol.document(staleUid).get().await() }.getOrNull()
        if (snap != null) {
          if (snap.exists()) {
            byUid[staleUid] = SarimWebPullMappers.mapToAppAccount(staleUid, snap.data ?: emptyMap())
          } else {
            byUid.remove(staleUid)
          }
        }
      }
      val sorted = byUid.values.sortedBy { it.email.lowercase() }
      Result.success(sorted)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.LIST, "users/$wsUid/settings/accounts")
      Result.failure(e)
    }
  }

  /**
   * Step 1 of App Account creation/adoption matching `_adminAddAccountRaw()` in `app-accounts.screen.html`.
   */
  suspend fun prepareAdminAccount(
    email: String,
    password: String,
    name: String,
    roles: List<String>,
    wsUid: String,
  ): Result<AdminAccountPrepareResult> {
    if (!isFirebaseConfigured) {
      return Result.failure(IllegalStateException("Firebase is not configured. Cloud account management requires Firebase."))
    }
    val cleanEmail = email.trim().lowercase()
    val cleanRoles = RolesHelper.cleanRoles(roles)
    val secondaryAuth = try {
      getOrCreateSecondaryAuth()
    } catch (e: Exception) {
      return Result.failure(e)
    }

    var uid = ""
    var adopted = false
    try {
      try {
        val cred = secondaryAuth.createUserWithEmailAndPassword(cleanEmail, password).await()
        uid = cred.user?.uid ?: throw IllegalStateException("Account creation failed to return UID.")
      } catch (collision: FirebaseAuthUserCollisionException) {
        try {
          val cred = secondaryAuth.signInWithEmailAndPassword(cleanEmail, password).await()
          uid = cred.user?.uid ?: throw IllegalStateException("Sign-in did not return UID.")
          adopted = true
        } catch (_: Exception) {
          return Result.success(
            AdminAccountPrepareResult.Blocked(
              title = "Email Already Registered",
              message = "This email already has a login, and the password you entered does not match it. Enter that login's current password to add it to your workspace.",
            )
          )
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return Result.success(
        AdminAccountPrepareResult.Blocked(
          title = "Could Not Create Login",
          message = mapAuthError(e),
        )
      )
    } finally {
      runCatching { secondaryAuth.signOut() }
    }

    if (adopted) {
      val existing = runCatching {
        db.collection("users").document(uid).get().await().let { if (it.exists()) it.data else null }
      }.getOrNull()
      val knownUids = readAccountsIndex(wsUid).mapNotNull { it["uid"]?.toString() }.toSet()
      val (canAdopt, _) = RolesHelper.canAdoptAccount(existing, uid, wsUid, knownUids)
      if (!canAdopt) {
        return Result.success(
          AdminAccountPrepareResult.Blocked(
            title = "Belongs to Another Workspace",
            message = "This login is part of a different workspace, so it cannot be added here.",
          )
        )
      }
      return Result.success(AdminAccountPrepareResult.NeedsAdoptConfirm(uid, cleanEmail, name.trim(), cleanRoles))
    }

    return Result.success(AdminAccountPrepareResult.Ready(uid, cleanEmail, name.trim(), cleanRoles))
  }

  /**
   * Step 2 of App Account creation/adoption: writes `/users/{uid}` and `/users/{wsUid}/settings/accounts`.
   */
  suspend fun finalizeAdminAccount(
    uid: String,
    email: String,
    name: String,
    roles: List<String>,
    wsUid: String,
  ): Result<AppAccountEntity> {
    if (!isFirebaseConfigured) {
      return Result.failure(IllegalStateException("Firebase is not configured."))
    }
    val cleanEmail = email.trim().lowercase()
    val cleanName = name.trim()
    val cleanRoles = RolesHelper.cleanRoles(roles)
    val actorEmail = auth.currentUser?.email ?: ""
    val now = System.currentTimeMillis()
    return try {
      val userDoc = linkedMapOf<String, Any>(
        "email" to cleanEmail,
        "displayName" to cleanName,
        "roles" to cleanRoles,
        "approved" to true,
        "createdBy" to actorEmail,
        "role" to FieldValue.delete(),
        "updatedAt" to now,
        "createdAt" to now,
      )
      if (uid != wsUid) {
        userDoc["ownerUid"] = wsUid
      }
      db.collection("users").document(uid).set(userDoc, SetOptions.merge()).await()

      val indexEntry = linkedMapOf<String, Any?>(
        "uid" to uid,
        "email" to cleanEmail,
        "name" to cleanName,
        "roles" to cleanRoles,
        "approved" to true,
        "createdAt" to now,
        "createdBy" to actorEmail,
      )
      if (uid != wsUid) {
        indexEntry["ownerUid"] = wsUid
      }
      patchAccountsIndex(wsUid, uid, indexEntry)

      Result.success(
        AppAccountEntity(
          id = uid,
          uid = uid,
          email = cleanEmail,
          name = cleanName,
          rolesCsv = cleanRoles.joinToString(",") { it.uppercase() },
          syncPending = false,
          createdAt = now,
          approved = true,
          ownerUid = if (uid != wsUid) wsUid else "",
          createdBy = actorEmail,
        )
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, "users/$uid")
      Result.failure(e)
    }
  }

  suspend fun adminUpdateAccountRoles(
    targetAcct: AppAccountEntity,
    nextRoles: List<String>,
    wsUid: String,
  ): Result<Unit> {
    if (!isFirebaseConfigured) {
      return Result.failure(IllegalStateException("Firebase is not configured."))
    }
    val cleanRoles = RolesHelper.cleanRoles(nextRoles)
    val targetUid = targetAcct.uid.ifBlank { targetAcct.id }
    return try {
      db.collection("users").document(targetUid).set(
        mapOf(
          "roles" to cleanRoles,
          "role" to FieldValue.delete(),
        ),
        SetOptions.merge(),
      ).await()

      val indexEntry = linkedMapOf<String, Any?>(
        "uid" to targetUid,
        "email" to targetAcct.email.lowercase(),
        "name" to targetAcct.name,
        "roles" to cleanRoles,
        "approved" to targetAcct.approved,
        "createdAt" to targetAcct.createdAt,
      )
      if (targetUid != wsUid && targetAcct.ownerUid.isNotBlank()) {
        indexEntry["ownerUid"] = targetAcct.ownerUid
      } else if (targetUid != wsUid) {
        indexEntry["ownerUid"] = wsUid
      }
      patchAccountsIndex(wsUid, targetUid, indexEntry)
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, "users/$targetUid")
      Result.failure(e)
    }
  }

  suspend fun adminToggleAccountApproval(
    targetAcct: AppAccountEntity,
    newApproved: Boolean,
    wsUid: String,
  ): Result<Unit> {
    if (!isFirebaseConfigured) {
      return Result.failure(IllegalStateException("Firebase is not configured."))
    }
    val targetUid = targetAcct.uid.ifBlank { targetAcct.id }
    val actorEmail = auth.currentUser?.email ?: ""
    return try {
      val update = linkedMapOf<String, Any>("approved" to newApproved)
      if (!newApproved) {
        update["forceLogout"] = mapOf("at" to System.currentTimeMillis(), "by" to actorEmail)
      }
      db.collection("users").document(targetUid).set(update, SetOptions.merge()).await()

      val cleanRoles = RolesHelper.parseRolesCsv(targetAcct.rolesCsv)
      val indexEntry = linkedMapOf<String, Any?>(
        "uid" to targetUid,
        "email" to targetAcct.email.lowercase(),
        "name" to targetAcct.name,
        "roles" to cleanRoles,
        "approved" to newApproved,
        "createdAt" to targetAcct.createdAt,
      )
      if (targetUid != wsUid) indexEntry["ownerUid"] = targetAcct.ownerUid.ifBlank { wsUid }
      patchAccountsIndex(wsUid, targetUid, indexEntry)
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, "users/$targetUid")
      Result.failure(e)
    }
  }

  suspend fun adminRemoveAccount(
    targetAcct: AppAccountEntity,
    wsUid: String,
  ): Result<Unit> {
    if (!isFirebaseConfigured) {
      return Result.failure(IllegalStateException("Firebase is not configured."))
    }
    val targetUid = targetAcct.uid.ifBlank { targetAcct.id }
    val actorEmail = auth.currentUser?.email ?: ""
    val now = System.currentTimeMillis()
    return try {
      val ref = db.collection("users").document(targetUid)
      ref.set(
        mapOf(
          "approved" to false,
          "removedAt" to now,
          "removedBy" to actorEmail,
          "forceLogout" to mapOf("at" to now, "by" to actorEmail),
        ),
        SetOptions.merge(),
      ).await()
      ref.delete().await()
      patchAccountsIndex(wsUid, targetUid, null)
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, "users/$targetUid")
      Result.failure(e)
    }
  }

  // =====================================================================
  // Sync Ping & Collection Push / Pull / Pending / Approvals
  // =====================================================================

  suspend fun emitSyncUpdate(collections: Collection<String>) {
    if (!isFirebaseConfigured || _auth?.currentUser == null || collections.isEmpty()) return
    val now = System.currentTimeMillis()
    if (now - lastEmitPingAt <= 2000L) return
    lastEmitPingAt = now
    try {
      val rootUid = resolveWorkspaceRootUid()
      db.collection("users").document(rootUid).set(
        mapOf(
          "lastWrite" to mapOf(
            "ts" to FieldValue.serverTimestamp(),
            "collections" to collections.distinct(),
          )
        ),
        SetOptions.merge(),
      ).await()
    } catch (_: Exception) {
      // Ping failures are non-fatal
    }
  }

  private val pushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** Admin devices only (web `registerAdminPush`): store this device's FCM token for the workspace. */
  suspend fun registerPushToken(): Result<Unit> {
    val ctx = appContext ?: return Result.failure(IllegalStateException("No context"))
    return try {
      val user = _auth?.currentUser ?: return Result.failure(IllegalStateException("Not signed in"))
      val rootUid = resolveWorkspaceRootUid()
      val token = FirebaseMessaging.getInstance().token.await()
      val docId = SarimPush.pushDocId(user.uid, SarimPush.deviceId(ctx))
      db.collection("users").document(rootUid).collection(SarimPush.COL_FCM_TOKENS).document(docId)
        .set(SarimPush.tokenDocMap(user.uid, token)).await()
      SarimPush.rememberTokenRoot(ctx, rootUid)
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w("Push", "Could not register push token: ${e.message}")
      Result.failure(e)
    }
  }

  /** Web `removePushToken`: call before signing out so a signed-out device stops getting alerts. */
  suspend fun removePushToken() {
    val ctx = appContext ?: return
    val root = SarimPush.tokenRoot(ctx) ?: return
    val uid = _auth?.currentUser?.uid
    if (uid != null) {
      runCatching {
        db.collection("users").document(root).collection(SarimPush.COL_FCM_TOKENS)
          .document(SarimPush.pushDocId(uid, SarimPush.deviceId(ctx))).delete().await()
      }
    }
    SarimPush.rememberTokenRoot(ctx, null)
    runCatching { FirebaseMessaging.getInstance().deleteToken().await() }
  }

  private fun notifyAdminsInBackground(pendingId: String) {
    pushScope.launch {
      runCatching {
        SarimPush.notifyAdmins({ _auth?.currentUser?.getIdToken(false)?.await()?.token }, pendingId)
      }
    }
  }

  private fun sanitizeForFirestore(v: Any?): Any? = when (v) {
    is Double -> if (v.isNaN() || v.isInfinite()) 0.0 else v
    is Float -> if (v.isNaN() || v.isInfinite()) 0.0 else v.toDouble()
    is Map<*, *> -> v.entries.associate { it.key.toString() to sanitizeForFirestore(it.value) }
    is Collection<*> -> v.map { sanitizeForFirestore(it) }
    else -> v
  }

  /**
   * Year close / restore (web `_commitMergedBatch`): delete [deleteIds] and write [writes] in
   * batches of 400. Documents are written exactly as given, so flags such as `isMerged` survive.
   */
  suspend fun commitCollectionChanges(
    collection: String,
    deleteIds: List<String>,
    writes: List<Map<String, Any?>>,
  ): Result<Int> {
    var path = "users/${auth.currentUser?.uid ?: "unauthenticated"}/$collection"
    return try {
      val rootUid = resolveWorkspaceRootUid()
      path = "users/$rootUid/$collection"
      val col = db.collection("users").document(rootUid).collection(collection)
      val ops = ArrayList<Pair<DocumentReference, Map<String, Any?>?>>(deleteIds.size + writes.size)
      deleteIds.filter { it.isNotBlank() }.forEach { ops += col.document(it) to null }
      writes.forEach { w ->
        val id = w["id"]?.toString().orEmpty()
        if (id.isNotBlank()) {
          @Suppress("UNCHECKED_CAST")
          ops += col.document(id) to (sanitizeForFirestore(w) as Map<String, Any?>)
        }
      }
      ops.chunked(400).forEach { chunk ->
        val batch = db.batch()
        chunk.forEach { (ref, data) -> if (data == null) batch.delete(ref) else batch.set(ref, data) }
        batch.commit().await()
      }
      recordQuota(writes = ops.size)
      Result.success(ops.size)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, path)
      Result.failure(e)
    }
  }

  /** Year close hard-deletes tombstones (web "Purging deleted records"). */
  suspend fun purgeDeletionTombstones(): Result<Int> {
    var path = "users/${auth.currentUser?.uid ?: "unauthenticated"}/${SarimWebSchema.COL_DELETIONS}"
    return try {
      val rootUid = resolveWorkspaceRootUid()
      path = "users/$rootUid/${SarimWebSchema.COL_DELETIONS}"
      val col = db.collection("users").document(rootUid).collection(SarimWebSchema.COL_DELETIONS)
      val docs = col.get().await().documents
      docs.chunked(400).forEach { chunk ->
        val batch = db.batch()
        chunk.forEach { batch.delete(it.reference) }
        batch.commit().await()
      }
      recordQuota(reads = docs.size.coerceAtLeast(1), writes = docs.size)
      Result.success(docs.size)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, path)
      Result.failure(e)
    }
  }

  suspend fun submitPendingBatch(
    items: List<PendingSubmitItem>,
    whoUid: String,
    whoName: String,
    whoEmail: String,
  ): Result<Int> {
    if (items.isEmpty()) return Result.success(0)
    var path = "users/${auth.currentUser?.uid ?: "unauthenticated"}/pending"
    return try {
      val rootUid = resolveWorkspaceRootUid()
      path = "users/$rootUid/pending"
      val now = System.currentTimeMillis()
      var count = 0
      items.chunked(350).forEach { chunk ->
        val batch = db.batch()
        chunk.forEach { item ->
          val pid = SarimWebSchema.pendingId(item.collection, item.docId)
          val payload = SarimWebSchema.buildPendingDocMap(
            collection = item.collection,
            docId = item.docId,
            action = item.action,
            data = item.data,
            whoUid = whoUid,
            whoName = whoName,
            whoEmail = whoEmail,
            nowMs = now,
            isEdit = item.isEdit,
          ).toMutableMap()
          payload["submittedAt"] = FieldValue.serverTimestamp()
          val ref = db.collection("users").document(rootUid)
            .collection(SarimWebSchema.COL_PENDING).document(pid)
          batch.set(ref, payload)
          count++
        }
        batch.commit().await()
      }
      recordQuota(writes = count)
      if (count > 0) notifyAdminsInBackground(SarimWebSchema.pendingId(items.first().collection, items.first().docId))
      Result.success(count)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, path)
      Result.failure(e)
    }
  }

  suspend fun commitApprovalsBatch(
    items: List<ApprovalRequestEntity>,
    deciderUid: String,
    deciderName: String,
  ): Result<Int> {
    if (items.isEmpty()) return Result.success(0)
    var path = "users/${auth.currentUser?.uid ?: "unauthenticated"}/pending"
    return try {
      val rootUid = resolveWorkspaceRootUid()
      path = "users/$rootUid/pending"
      val userRef = db.collection("users").document(rootUid)
      val decider = mapOf("uid" to deciderUid, "name" to deciderName)
      var count = 0

      // 3 writes per item -> max 130 items (~390 ops) per Firestore batch
      items.chunked(130).forEach { chunk ->
        val batch = db.batch()
        chunk.forEach { req ->
          val col = req.targetCollection.trim()
          val docId = req.docId.trim()
          if (col.isNotBlank() && docId.isNotBlank()) {
            val targetRef = userRef.collection(col).document(docId)
            val delRef = userRef.collection(SarimWebSchema.COL_DELETIONS).document(docId)
            if (req.action == "delete") {
              batch.delete(targetRef)
              batch.set(
                delRef,
                mapOf(
                  "id" to docId,
                  "recordId" to docId,
                  "collection" to col,
                  "recordType" to col,
                  "deletedAt" to FieldValue.serverTimestamp(),
                  "expiresAt" to Timestamp(Date(System.currentTimeMillis() + SarimWebSchema.TOMBSTONE_EXPIRY_MS)),
                  "deleted_by" to deciderName.ifBlank { "Admin" },
                  "deletion_version" to "2.0",
                ),
                SetOptions.merge(),
              )
            } else {
              val rawMap = runCatching {
                SarimWebSchema.jsonObjectToMap(JSONObject(req.payloadJson))
              }.getOrDefault(emptyMap())
                .filterValues { it != null }
                .toMutableMap()
              rawMap["id"] = docId
              rawMap["approvedBy"] = deciderName.ifBlank { "Admin" }
              rawMap["updatedAt"] = FieldValue.serverTimestamp()
              batch.set(targetRef, rawMap, SetOptions.merge())
              batch.delete(delRef)
            }
          }
          batch.set(
            userRef.collection(SarimWebSchema.COL_PENDING).document(req.id),
            mapOf(
              "status" to "approved",
              "decidedBy" to decider,
              "decidedAt" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
          )
          count++
        }
        batch.commit().await()
      }
      recordQuota(writes = count * 3)
      Result.success(count)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, path)
      Result.failure(e)
    }
  }

  suspend fun discardApprovalsBatch(
    items: List<ApprovalRequestEntity>,
    deciderUid: String,
    deciderName: String,
  ): Result<Int> {
    if (items.isEmpty()) return Result.success(0)
    var path = "users/${auth.currentUser?.uid ?: "unauthenticated"}/pending"
    return try {
      val rootUid = resolveWorkspaceRootUid()
      path = "users/$rootUid/pending"
      val userRef = db.collection("users").document(rootUid)
      val decider = mapOf("uid" to deciderUid, "name" to deciderName)
      var count = 0
      items.chunked(350).forEach { chunk ->
        val batch = db.batch()
        chunk.forEach { req ->
          batch.set(
            userRef.collection(SarimWebSchema.COL_PENDING).document(req.id),
            mapOf(
              "status" to "discarded",
              "decidedBy" to decider,
              "decidedAt" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
          )
          count++
        }
        batch.commit().await()
      }
      recordQuota(writes = count)
      Result.success(count)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, path)
      Result.failure(e)
    }
  }

  suspend fun deleteOwnPendingDoc(pendingId: String) {
    val rootUid = runCatching { resolveWorkspaceRootUid() }.getOrNull() ?: return
    runCatching {
      db.collection("users").document(rootUid)
        .collection(SarimWebSchema.COL_PENDING).document(pendingId)
        .delete().await()
    }
  }

  suspend fun fetchSingleCloudDoc(collection: String, docId: String): Result<Map<String, Any>?> {
    var path = "users/${auth.currentUser?.uid ?: "unauthenticated"}/$collection/$docId"
    return try {
      val rootUid = resolveWorkspaceRootUid()
      path = "users/$rootUid/$collection/$docId"
      val snap = db.collection("users").document(rootUid)
        .collection(collection).document(docId)
        .get().await()
      recordQuota(reads = 1)
      Result.success(if (snap.exists()) snap.data else null)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.GET, path)
      Result.failure(e)
    }
  }

  suspend fun pushCollectionBatch(
    collectionName: String,
    docs: List<Pair<String, Map<String, Any?>>>,
  ): Result<Int> {
    if (docs.isEmpty()) return Result.success(0)
    var path = "users/${auth.currentUser?.uid ?: "unauthenticated"}/$collectionName"
    return try {
      val rootUid = resolveWorkspaceRootUid()
      path = "users/$rootUid/$collectionName"
      var count = 0
      docs.chunked(400).forEach { chunk ->
        val batch = db.batch()
        chunk.forEach { (docId, rawMap) ->
          val docRef = db.collection("users").document(rootUid)
            .collection(collectionName).document(docId)
          val payload = rawMap.filterValues { it != null }.toMutableMap()
          if (!payload.containsKey("updatedAt")) {
            payload["updatedAt"] = FieldValue.serverTimestamp()
          }
          batch.set(docRef, payload, SetOptions.merge())
          count++
        }
        batch.commit().await()
      }
      recordQuota(writes = count)
      Result.success(count)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, path)
      Result.failure(e)
    }
  }

  suspend fun pushSingletonDoc(
    collectionName: String,
    docId: String,
    data: Map<String, Any?>,
  ): Result<Unit> {
    var path = "users/${auth.currentUser?.uid ?: "unauthenticated"}/$collectionName/$docId"
    return try {
      val rootUid = resolveWorkspaceRootUid()
      path = "users/$rootUid/$collectionName/$docId"
      val payload = data.filterValues { it != null }
      db.collection("users").document(rootUid)
        .collection(collectionName).document(docId)
        .set(payload, SetOptions.merge())
        .await()
      recordQuota(writes = 1)
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, path)
      Result.failure(e)
    }
  }

  suspend fun fetchSingletonDoc(
    collectionName: String,
    docId: String,
  ): Result<Map<String, Any>?> {
    var path = "users/${auth.currentUser?.uid ?: "unauthenticated"}/$collectionName/$docId"
    return try {
      val rootUid = resolveWorkspaceRootUid()
      path = "users/$rootUid/$collectionName/$docId"
      val snap = db.collection("users").document(rootUid)
        .collection(collectionName).document(docId)
        .get()
        .await()
      recordQuota(reads = 1)
      Result.success(if (snap.exists()) snap.data else null)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.GET, path)
      Result.failure(e)
    }
  }

  suspend fun fetchCollectionDocs(collectionName: String): Result<List<Pair<String, Map<String, Any>>>> {
    var path = "users/${auth.currentUser?.uid ?: "unauthenticated"}/$collectionName"
    return try {
      val rootUid = resolveWorkspaceRootUid()
      path = "users/$rootUid/$collectionName"
      val snap = db.collection("users").document(rootUid)
        .collection(collectionName)
        .get()
        .await()
      val items = snap.documents.mapNotNull { doc ->
        doc.data?.let { data ->
          if (SarimWebSchema.bool(data["_placeholder"])) null else (doc.id to data)
        }
      }
      recordQuota(reads = snap.size().coerceAtLeast(1))
      Result.success(items)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.LIST, path)
      Result.failure(e)
    }
  }

  suspend fun fetchOwnPendingDocs(selfUid: String): Result<List<Pair<String, Map<String, Any>>>> {
    var path = "users/${auth.currentUser?.uid ?: "unauthenticated"}/pending"
    return try {
      val rootUid = resolveWorkspaceRootUid()
      path = "users/$rootUid/pending"
      val snap = db.collection("users").document(rootUid)
        .collection(SarimWebSchema.COL_PENDING)
        .whereEqualTo("submittedBy.uid", selfUid)
        .get()
        .await()
      val items = snap.documents.mapNotNull { doc -> doc.data?.let { doc.id to it } }
      recordQuota(reads = snap.size().coerceAtLeast(1))
      Result.success(items)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.LIST, path)
      Result.failure(e)
    }
  }

  suspend fun pushDeletionTombstones(tombstones: List<DeletionTombstoneEntity>): Result<Int> {
    if (tombstones.isEmpty()) return Result.success(0)
    var path = "users/${auth.currentUser?.uid ?: "unauthenticated"}/deletions"
    return try {
      val rootUid = resolveWorkspaceRootUid()
      path = "users/$rootUid/deletions"
      val singletonCollections = setOf(
        SarimWebSchema.COL_FORMULAS,
        SarimWebSchema.COL_DAILY_LEDGER,
        SarimWebSchema.COL_STORES,
      )
      var count = 0
      tombstones.chunked(200).forEach { chunk ->
        val batch = db.batch()
        chunk.forEach { d ->
          val delRef = db.collection("users").document(rootUid)
            .collection(SarimWebSchema.COL_DELETIONS).document(d.id)
          val expiresMs = if (d.expiresAt > 0L) d.expiresAt else (d.deletedAt + SarimWebSchema.TOMBSTONE_EXPIRY_MS)
          val payload = linkedMapOf<String, Any>(
            "id" to d.id,
            "recordId" to d.recordId,
            "collection" to d.collection,
            "recordType" to d.recordType,
            "deletedAt" to Timestamp(Date(d.deletedAt)),
            "expiresAt" to Timestamp(Date(expiresMs)),
            "tombstoned_at" to d.deletedAt,
            "deleted_by" to d.deletedBy.ifBlank { "Admin" },
            "deletion_version" to d.deletionVersion.ifBlank { "2.0" },
          )
          d.displayName?.let { payload["displayName"] = it }
          d.displayDetail?.let { payload["displayDetail"] = it }
          d.displayAmount?.let { payload["displayAmount"] = it }
          d.groupId?.let { payload["_groupId"] = it }
          if (!d.snapshotJson.isNullOrBlank()) {
            runCatching {
              SarimWebSchema.jsonObjectToMap(JSONObject(d.snapshotJson)).filterValues { it != null }
            }.getOrNull()?.let { snapMap ->
              payload["snapshot"] = snapMap
            }
          }
          batch.set(delRef, payload, SetOptions.merge())
          if (d.collection.isNotBlank() && d.collection != "unknown" && d.collection !in singletonCollections) {
            batch.delete(
              db.collection("users").document(rootUid).collection(d.collection).document(d.recordId)
            )
          }
          count++
        }
        batch.commit().await()
      }
      recordQuota(writes = count * 2)
      Result.success(count)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, path)
      Result.failure(e)
    }
  }

  /**
   * Mirrors `_drainOutbox()` in `modules/business.js`, with gated-session routing into `/pending`.
   */
  suspend fun drainOutboxOp(
    op: NdappOutboxEntity,
    isGated: Boolean = false,
    whoUid: String = "",
    whoName: String = "",
    whoEmail: String = "",
    isEdit: Boolean = false,
  ): Result<Unit> {
    val rootUid = try {
      resolveWorkspaceRootUid()
    } catch (e: Exception) {
      return Result.failure(e)
    }
    if (op.collection.isBlank() || op.docId.isBlank()) return Result.success(Unit)
    if (isGated) {
      if (!SarimWebSchema.isGatedCollection(op.collection)) {
        // Non-admins cannot write to admin-only singletons (stores/formulas/daily_ledger/settings/deletions).
        return Result.success(Unit)
      }
      val dataMap = if (op.action == "delete" || op.payload.isNullOrBlank()) {
        null
      } else {
        SarimWebSchema.jsonObjectToMap(JSONObject(op.payload))
      }
      return submitPendingBatch(
        items = listOf(
          PendingSubmitItem(
            collection = op.collection,
            docId = op.docId,
            action = if (op.action == "delete") "delete" else "set",
            data = dataMap,
            isEdit = isEdit,
          )
        ),
        whoUid = whoUid.ifBlank { auth.currentUser?.uid.orEmpty() },
        whoName = whoName.ifBlank { auth.currentUser?.displayName.orEmpty() },
        whoEmail = whoEmail.ifBlank { auth.currentUser?.email.orEmpty() },
      ).map { Unit }
    }

    val docRef = db.collection("users").document(rootUid)
      .collection(op.collection).document(op.docId)
    return try {
      when (op.action) {
        "delete" -> {
          docRef.delete().await()
        }
        else -> {
          val rawMap = if (!op.payload.isNullOrBlank()) {
            SarimWebSchema.jsonObjectToMap(JSONObject(op.payload)).filterValues { it != null }.toMutableMap()
          } else {
            mutableMapOf()
          }
          if (!rawMap.containsKey("updatedAt")) {
            rawMap["updatedAt"] = FieldValue.serverTimestamp()
          }
          docRef.set(rawMap, SetOptions.merge()).await()
        }
      }
      recordQuota(writes = 1)
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, "users/$rootUid/${op.collection}/${op.docId}")
      Result.failure(e)
    }
  }

  suspend fun fetchLiveDatabaseDiagnostics(wsUid: String): Result<LiveFirestoreDiagnostics> {
    if (!isFirebaseConfigured || _auth?.currentUser == null || wsUid.isBlank()) {
      return Result.failure(IllegalStateException("Not authenticated"))
    }
    return try {
      val userRef = db.collection("users").document(wsUid)
      val colCounts = linkedMapOf<String, Int>()
      val cols = SarimWebSchema.RECORD_COLLECTIONS + listOf(SarimWebSchema.COL_DELETIONS, SarimWebSchema.COL_PHOTOS)
      var totalReads = 0
      cols.forEach { colName ->
        val snap = runCatching { userRef.collection(colName).get().await() }.getOrNull()
        if (snap != null) {
          val count = snap.documents.count { doc -> !SarimWebSchema.bool(doc.data?.get("_placeholder")) }
          colCounts[colName] = count
          totalReads += snap.size().coerceAtLeast(1)
        }
      }

      val configPaths = listOf(
        "settings/config",
        "settings/team",
        "settings/yearCloseSignal",
        "formulas/config",
        "categories/list",
        "stores/list",
        "account/info",
      )
      val cfgStates = linkedMapOf<String, LiveConfigDocCloudState>()
      configPaths.forEach { p ->
        val parts = p.split("/")
        val snap = runCatching { userRef.collection(parts[0]).document(parts[1]).get().await() }.getOrNull()
        totalReads += 1
        if (snap != null && snap.exists()) {
          val data = snap.data ?: emptyMap()
          val previews = data.entries.associate { (k, v) ->
            k to when (v) {
              is List<*> -> "${v.size} item(s)"
              is Map<*, *> -> "${v.size} key(s)"
              else -> v?.toString()?.take(36) ?: ""
            }
          }
          cfgStates[p] = LiveConfigDocCloudState(
            exists = true,
            presentFields = data.keys.toSet(),
            fieldPreviews = previews,
          )
        } else {
          cfgStates[p] = LiveConfigDocCloudState(exists = false)
        }
      }
      recordQuota(reads = totalReads)
      Result.success(
        LiveFirestoreDiagnostics(
          collectionCounts = colCounts,
          configDocs = cfgStates,
        )
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  // =====================================================================
  // Realtime Snapshot Listeners Coordinator (mirrors subscribeToRealtime in sync.js)
  // =====================================================================

  fun stopRealtimeListeners() {
    synchronized(listenerLock) {
      activeListeners.forEach { reg -> runCatching { reg.remove() } }
      activeListeners.clear()
    }
  }

  fun startRealtimeListeners(
    selfUid: String,
    wsUid: String,
    selfEmail: String,
    isAdmin: Boolean,
    isGated: Boolean,
    callbacks: RealtimeSyncCallbacks,
  ) {
    stopRealtimeListeners()
    if (!isFirebaseConfigured || selfUid.isBlank() || wsUid.isBlank()) return

    val regs = mutableListOf<ListenerRegistration>()
    val selfRef = db.collection("users").document(selfUid)
    val userRef = db.collection("users").document(wsUid)
    val sharedRoot = selfUid == wsUid

    fun handlePing(data: Map<String, Any?>, hasPendingWrites: Boolean, fromCache: Boolean) {
      if (hasPendingWrites || fromCache) return
      val lw = data["lastWrite"] as? Map<*, *> ?: return
      val pingTs = SarimWebSchema.parseEpoch(lw["ts"])
      if (pingTs > lastSeenPingTs + 1000L) {
        lastSeenPingTs = pingTs
        if (lastEmitPingAt == 0L || kotlin.math.abs(pingTs - lastEmitPingAt) > 3000L) {
          val cols = (lw["collections"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
          callbacks.onRemotePing(cols)
        }
      }
    }

    // 1. Self doc watch (account removal, suspension, forceLogout, live role updates)
    regs += selfRef.addSnapshotListener { snap, err ->
      if (err != null) {
        Log.w("SarimSync", "self listener error: ${err.code} ${err.message}")
        return@addSnapshotListener
      }
      if (snap == null) return@addSnapshotListener
      if (!snap.exists()) {
        if (!snap.metadata.isFromCache && callbacks.isCurrentlyOnline()) {
          callbacks.onForceSignOut("Your account has been removed. Signing out…")
        }
        return@addSnapshotListener
      }
      val data = snap.data ?: emptyMap()
      val fl = data["forceLogout"] as? Map<*, *>
      val forceAt = fl?.get("at")?.let { SarimWebSchema.parseEpoch(it) } ?: 0L
      val sessionStart = callbacks.getSessionStartMillis()
      if (forceAt > 0L && sessionStart > 0L && forceAt > sessionStart) {
        callbacks.onForceSignOut("Your account access has been revoked. Signing out…")
        return@addSnapshotListener
      }
      if (data["approved"] == false) {
        callbacks.onForceSignOut("Your account has been suspended. Signing out…")
        return@addSnapshotListener
      }
      if (!snap.metadata.isFromCache) {
        val updated = RolesHelper.accountInfoFromDoc(
          data = data,
          uid = selfUid,
          email = selfEmail,
        )
        callbacks.onSessionDocChanged(updated)
      }
      if (sharedRoot) {
        handlePing(data, snap.metadata.hasPendingWrites(), snap.metadata.isFromCache)
      }
    }

    // 2. Workspace root ping watch when member != owner
    if (!sharedRoot) {
      regs += userRef.addSnapshotListener { snap, err ->
        if (err != null || snap == null || !snap.exists()) return@addSnapshotListener
        handlePing(snap.data ?: emptyMap(), snap.metadata.hasPendingWrites(), snap.metadata.isFromCache)
      }
    }

    // 3. Pending / Approvals listener
    if (isGated) {
      regs += userRef.collection(SarimWebSchema.COL_PENDING)
        .whereEqualTo("submittedBy.uid", selfUid)
        .addSnapshotListener { snap, err ->
          if (err != null || snap == null || snap.metadata.isFromCache) return@addSnapshotListener
          snap.documentChanges.forEach { ch ->
            if (ch.type != DocumentChange.Type.REMOVED) {
              val data = ch.document.data
              SarimWebPullMappers.mapToApproval(ch.document.id, data)?.let { mapped ->
                if (mapped.status == "APPROVED" || mapped.status == "DISCARDED") {
                  callbacks.onOwnPendingResolved(mapped)
                }
              }
            }
          }
        }
    } else if (isAdmin) {
      regs += userRef.collection(SarimWebSchema.COL_PENDING)
        .whereEqualTo("status", "pending")
        .addSnapshotListener { snap, err ->
          if (err != null || snap == null || snap.metadata.isFromCache) return@addSnapshotListener
          val list = snap.documents.mapNotNull { doc ->
            SarimWebPullMappers.mapToApproval(doc.id, doc.data ?: emptyMap())
          }.sortedBy { it.createdAt }
          callbacks.onAdminPendingSnapshot(list)
        }
    }

    // 4. Settings, Team & YearCloseSignal
    regs += userRef.collection(SarimWebSchema.COL_SETTINGS).document("config")
      .addSnapshotListener { doc, err ->
        if (err != null || doc == null || !doc.exists() || doc.metadata.hasPendingWrites() || doc.metadata.isFromCache) return@addSnapshotListener
        doc.data?.let { callbacks.onSettingsConfigDoc(it) }
      }

    regs += userRef.collection(SarimWebSchema.COL_SETTINGS).document("team")
      .addSnapshotListener { doc, err ->
        if (err != null || doc == null || !doc.exists() || doc.metadata.hasPendingWrites() || doc.metadata.isFromCache) return@addSnapshotListener
        doc.data?.let { callbacks.onTeamDoc(it) }
      }

    regs += userRef.collection(SarimWebSchema.COL_SETTINGS).document("yearCloseSignal")
      .addSnapshotListener { doc, err ->
        if (err != null || doc == null || !doc.exists() || doc.metadata.hasPendingWrites() || doc.metadata.isFromCache) return@addSnapshotListener
        doc.data?.let { callbacks.onYearCloseSignal(it) }
      }

    // 5. Singleton collections: stores, formulas, daily_ledger, categories
    regs += userRef.collection(SarimWebSchema.COL_STORES).document("list")
      .addSnapshotListener { doc, err ->
        if (err != null || doc == null || !doc.exists() || doc.metadata.hasPendingWrites() || doc.metadata.isFromCache) return@addSnapshotListener
        doc.data?.let { callbacks.onStoresDoc(it) }
      }

    regs += userRef.collection(SarimWebSchema.COL_FORMULAS).document("config")
      .addSnapshotListener { doc, err ->
        if (err != null || doc == null || !doc.exists() || doc.metadata.hasPendingWrites() || doc.metadata.isFromCache) return@addSnapshotListener
        doc.data?.let { callbacks.onFormulasDoc(it) }
      }

    regs += userRef.collection(SarimWebSchema.COL_DAILY_LEDGER).document("entries")
      .addSnapshotListener { doc, err ->
        if (err != null || doc == null || !doc.exists() || doc.metadata.hasPendingWrites() || doc.metadata.isFromCache) return@addSnapshotListener
        doc.data?.let { callbacks.onDailyLedgerDoc(it) }
      }

    listOf(
      SarimWebSchema.COL_CATEGORIES,
      SarimWebSchema.COL_FIELD_EXP_CATS,
      SarimWebSchema.COL_COMM_CATS,
    ).forEach { catCol ->
      regs += userRef.collection(catCol).document("list")
        .addSnapshotListener { doc, err ->
          if (err != null || doc == null || !doc.exists() || doc.metadata.hasPendingWrites() || doc.metadata.isFromCache) return@addSnapshotListener
          doc.data?.let { callbacks.onCategoryDoc(catCol, it) }
        }
    }

    // 6. Deletions & Photos
    regs += userRef.collection(SarimWebSchema.COL_DELETIONS)
      .addSnapshotListener { snap, err ->
        if (err != null || snap == null || snap.metadata.hasPendingWrites() || snap.metadata.isFromCache) return@addSnapshotListener
        val upserts = mutableListOf<Pair<String, Map<String, Any>>>()
        val removedIds = mutableListOf<String>()
        snap.documentChanges.forEach { ch ->
          val doc = ch.document
          if (ch.type == DocumentChange.Type.REMOVED) {
            removedIds += doc.id
          } else {
            val d = doc.data
            if (!SarimWebSchema.bool(d["_placeholder"])) {
              upserts += doc.id to d
            }
          }
        }
        if (upserts.isNotEmpty() || removedIds.isNotEmpty()) {
          callbacks.onDeletionsSnapshot(upserts, removedIds)
        }
      }

    regs += userRef.collection(SarimWebSchema.COL_PHOTOS)
      .addSnapshotListener { snap, err ->
        if (err != null || snap == null || snap.metadata.hasPendingWrites() || snap.metadata.isFromCache) return@addSnapshotListener
        val upserts = snap.documentChanges.mapNotNull { ch ->
          if (ch.type == DocumentChange.Type.REMOVED) null
          else ch.document.id to ch.document.data
        }
        if (upserts.isNotEmpty()) {
          callbacks.onPhotosSnapshot(upserts)
        }
      }

    // 7. Record Collections incremental snapshots
    SarimWebSchema.RECORD_COLLECTIONS.forEach { col ->
      regs += userRef.collection(col)
        .addSnapshotListener { snap, err ->
          if (err != null || snap == null || snap.metadata.hasPendingWrites() || snap.metadata.isFromCache) return@addSnapshotListener
          val docs = snap.documents.mapNotNull { d ->
            val data = d.data ?: return@mapNotNull null
            if (SarimWebSchema.bool(data["_placeholder"])) null else d.id to data
          }
          val removed = snap.documentChanges
            .filter { it.type == DocumentChange.Type.REMOVED }
            .map { it.document.id }
          callbacks.onRecordCollectionSnapshot(col, docs, removed)
        }
    }

    synchronized(listenerLock) {
      activeListeners.addAll(regs)
    }
  }
}
