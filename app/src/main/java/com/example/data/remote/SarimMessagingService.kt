package com.example.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class SarimMessagingService : FirebaseMessagingService() {

  /** Foreground delivery. In the background the system draws the notification itself. */
  override fun onMessageReceived(message: RemoteMessage) {
    val type = message.data[SarimPush.EXTRA_TYPE]
    val target = SarimPush.parseTarget(
      raw = message.data[SarimPush.EXTRA_TARGET],
      explicitKey = message.data[SarimPush.EXTRA_TARGET_KEY],
      explicitId = message.data[SarimPush.EXTRA_TARGET_ID],
    )
    when {
      type == SarimPush.TYPE_APPROVAL -> {
        SarimPush.showApprovalNotification(
          context = applicationContext,
          title = message.notification?.title ?: message.data["title"] ?: "Approval needed",
          body = message.notification?.body ?: message.data["body"] ?: "Something is waiting for your approval",
          pendingId = message.data[SarimPush.EXTRA_PENDING_ID],
        )
      }
      type == SarimPush.TYPE_ACTIVITY || target != null -> {
        SarimPush.showRemoteActivityNotification(
          context = applicationContext,
          title = message.notification?.title ?: message.data["title"] ?: "Team activity",
          body = message.notification?.body ?: message.data["body"] ?: "A teammate added or updated a record",
          target = target,
          tag = message.data["tag"] ?: "fcm-${target?.k ?: "act"}-${target?.id ?: System.currentTimeMillis()}",
        )
      }
    }
  }

  /** Keep the stored token fresh when Firebase rotates it (only for a device that already registered). */
  override fun onNewToken(token: String) {
    val root = SarimPush.tokenRoot(applicationContext) ?: return
    val user = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull() ?: return
    val docId = SarimPush.pushDocId(user.uid, SarimPush.deviceId(applicationContext))
    runCatching {
      FirebaseFirestore.getInstance().collection("users").document(root)
        .collection(SarimPush.COL_FCM_TOKENS).document(docId)
        .set(SarimPush.tokenDocMap(user.uid, token))
    }
  }
}
