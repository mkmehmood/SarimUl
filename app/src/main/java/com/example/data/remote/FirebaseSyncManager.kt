package com.example.data.remote

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.example.data.local.DeletionTombstoneEntity
import com.example.data.local.NdappOutboxEntity
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
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

fun handleFirestoreError(exception: Throwable, operationType: OperationType, path: String?): String {
  val causes = generateSequence(exception) { it.cause }
  val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
    ?: causes.firstOrNull { it !is CancellationException }
    ?: exception

  val auth = Firebase.auth
  val currentUser = auth.currentUser

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
  private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
  private val auth: FirebaseAuth = Firebase.auth,
) {
  @Volatile private var cachedWorkspaceUid: Pair<String, String>? = null

  fun currentUser(): FirebaseUser? = auth.currentUser

  private fun requireUserId(): String {
    val uid = auth.currentUser?.uid
    if (uid.isNullOrBlank()) {
      throw IllegalStateException("User must be signed in with Google before syncing with Firestore.")
    }
    return uid
  }

  /**
   * Mirrors `dataRootUid()` from web repo `modules/business.js`:
   * uses `ownerUid` on `/users/{uid}` when the signed-in user belongs to an owner's workspace,
   * otherwise defaults to `auth.currentUser.uid`.
   */
  suspend fun resolveWorkspaceRootUid(): String {
    val uid = requireUserId()
    cachedWorkspaceUid?.let { (forUid, ownerUid) ->
      if (forUid == uid) return ownerUid
    }
    val resolved = try {
      val userDoc = db.collection("users").document(uid).get().await()
      val owner = userDoc.getString("ownerUid")?.trim()
      if (!owner.isNullOrBlank()) owner else uid
    } catch (_: Exception) {
      uid
    }
    cachedWorkspaceUid = uid to resolved
    return resolved
  }

  fun attemptAutoSignIn(
    context: Context,
    credentialManager: CredentialManager,
    scope: CoroutineScope,
    onAuthSuccess: () -> Unit,
    onUnauthenticated: () -> Unit,
  ) {
    if (auth.currentUser != null) {
      onAuthSuccess()
      return
    }
    val clientId = try {
      context.getString(R.string.default_web_client_id)
    } catch (_: Exception) {
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
    val clientId = try {
      context.getString(R.string.default_web_client_id)
    } catch (_: Exception) {
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
    scope.launch {
      withContext(NonCancellable) {
        try {
          credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
          Log.w("Auth", "Failed to clear credential state: ${e.message}")
        } finally {
          cachedWorkspaceUid = null
          auth.signOut()
          onSignOutComplete()
        }
      }
    }
  }

  suspend fun signInWithEmailPassword(email: String, password: String): Result<FirebaseUser> {
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

  suspend fun registerWithEmailPassword(
    displayName: String,
    email: String,
    password: String,
  ): Result<FirebaseUser> {
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
      Result.success(count)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, path)
      Result.failure(e)
    }
  }

  /**
   * Mirrors `_drainOutbox()` in web repo `modules/business.js`.
   */
  suspend fun drainOutboxOp(op: NdappOutboxEntity): Result<Unit> {
    val rootUid = try {
      resolveWorkspaceRootUid()
    } catch (e: Exception) {
      return Result.failure(e)
    }
    if (op.collection.isBlank() || op.docId.isBlank()) return Result.success(Unit)
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
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, "users/$rootUid/${op.collection}/${op.docId}")
      Result.failure(e)
    }
  }
}
