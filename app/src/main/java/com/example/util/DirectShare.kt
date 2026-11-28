package com.example.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.FileProvider
import java.io.File

/** Port of the web app's `phone-format.js` `toInternationalDigits` (Pakistan default). */
fun toInternationalDigits(raw: String?, countryCode: String = "92"): String {
  var d = (raw ?: "").filter { it.isDigit() }
  if (d.isEmpty()) return ""
  d = when {
    d.startsWith("00") -> d.drop(2)
    d.startsWith("0") -> countryCode + d.drop(1)
    d.length == 10 && d.startsWith("3") -> countryCode + d
    else -> d
  }
  return if (d.length in 8..15) d else ""
}

enum class ShareOutcome { SHARED_DIRECT, SHARED_CHOOSER, NOT_INSTALLED, FAILED }

/**
 * Prevents background-resume biometric re-lock from firing immediately when the user leaves the app
 * intentionally for a system picker, share sheet, dialer, or maps app.
 */
object AppLockGuard {
  @Volatile private var suppressUntilMs: Long = 0L

  fun suppressFor(durationMs: Long = 90_000L) {
    val next = System.currentTimeMillis() + durationMs
    if (next > suppressUntilMs) suppressUntilMs = next
  }

  fun isSuppressed(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs < suppressUntilMs

  fun consumeIfSuppressed(nowMs: Long = System.currentTimeMillis()): Boolean {
    val active = nowMs < suppressUntilMs
    suppressUntilMs = 0L
    return active
  }
}

/**
 * Port of the web app's `native.js` + `DirectSharePlugin`: send files or text straight to a
 * WhatsApp chat (WhatsApp / WhatsApp Business), open a chat, or open the SMS composer, falling
 * back to the system share sheet when the target app is missing.
 */
object DirectShare {
  private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

  fun installedWhatsApp(context: Context): String? {
    val pm = context.packageManager
    return WHATSAPP_PACKAGES.firstOrNull { pkg ->
      runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
      }.isSuccess
    }
  }

  private fun uriFor(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

  /** Open a WhatsApp chat with [phone]; falls back to the wa.me link. Returns false if nothing could open. */
  fun openWhatsAppChat(context: Context, phone: String?): Boolean {
    val digits = toInternationalDigits(phone)
    if (digits.isEmpty()) return false
    AppLockGuard.suppressFor()
    val pkg = installedWhatsApp(context)
    val direct = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send?phone=${Uri.encode(digits)}"))
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (pkg != null) {
      direct.setPackage(pkg)
      if (runCatching { context.startActivity(direct) }.isSuccess) return true
    }
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(web) }.isSuccess
  }

  /**
   * Share image / PDF files to WhatsApp, straight into [phone]'s chat when given
   * (web `shareToWhatsApp`). Falls back to the system chooser if WhatsApp is not installed.
   */
  fun shareFiles(
    context: Context,
    files: List<File>,
    mime: String,
    phone: String? = null,
    text: String = "",
    title: String = "Share",
  ): ShareOutcome {
    if (files.isEmpty() || files.any { !it.exists() }) return ShareOutcome.FAILED
    val streams = ArrayList(files.map { uriFor(context, it) })
    fun build(): Intent {
      val intent = if (streams.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, streams[0])
      } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams)
      }
      intent.type = mime
      val clip = ClipData.newRawUri("share", streams[0])
      for (i in 1 until streams.size) clip.addItem(ClipData.Item(streams[i]))
      intent.clipData = clip
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
      if (text.isNotEmpty()) intent.putExtra(Intent.EXTRA_TEXT, text)
      return intent
    }
    val pkg = installedWhatsApp(context)
    if (pkg != null) {
      try {
        AppLockGuard.suppressFor()
        val intent = build()
        val digits = toInternationalDigits(phone)
        if (digits.isNotEmpty()) intent.putExtra("jid", "$digits@s.whatsapp.net")
        intent.setPackage(pkg)
        context.startActivity(intent)
        return ShareOutcome.SHARED_DIRECT
      } catch (_: ActivityNotFoundException) {
      } catch (_: Exception) {
        return ShareOutcome.FAILED
      }
    }
    return try {
      AppLockGuard.suppressFor()
      val chooser = Intent.createChooser(build(), title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(chooser)
      ShareOutcome.SHARED_CHOOSER
    } catch (_: Exception) {
      ShareOutcome.FAILED
    }
  }

  /** Open the default messages app with [text] prefilled (web `sendText`). */
  fun sendSms(context: Context, text: String, phone: String? = null): ShareOutcome {
    return try {
      AppLockGuard.suppressFor()
      val digits = toInternationalDigits(phone)
      val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${if (digits.isNotEmpty()) "+$digits" else ""}"))
        .putExtra("sms_body", text)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      Telephony.Sms.getDefaultSmsPackage(context)?.let { intent.setPackage(it) }
      context.startActivity(intent)
      ShareOutcome.SHARED_DIRECT
    } catch (_: ActivityNotFoundException) {
      ShareOutcome.NOT_INSTALLED
    } catch (_: Exception) {
      ShareOutcome.FAILED
    }
  }

  /** Send [text] to a WhatsApp chat (or the contact picker when [phone] is blank), else system share. */
  fun shareTextToWhatsApp(context: Context, text: String, phone: String? = null, title: String = "Share"): ShareOutcome {
    val digits = toInternationalDigits(phone)
    val pkg = installedWhatsApp(context)
    if (pkg != null) {
      AppLockGuard.suppressFor()
      val uri = "whatsapp://send?" + (if (digits.isNotEmpty()) "phone=${Uri.encode(digits)}&" else "") + "text=${Uri.encode(text)}"
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      if (runCatching { context.startActivity(intent) }.isSuccess) return ShareOutcome.SHARED_DIRECT
    }
    return try {
      AppLockGuard.suppressFor()
      val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, title)
      context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      ShareOutcome.SHARED_CHOOSER
    } catch (_: Exception) {
      ShareOutcome.FAILED
    }
  }

  /** Plain system share sheet for one file (used for "PDF" – no WhatsApp targeting). */
  fun shareViaChooser(context: Context, file: File, mime: String, title: String = "Share"): ShareOutcome {
    if (!file.exists()) return ShareOutcome.FAILED
    return try {
      AppLockGuard.suppressFor()
      val send = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      ShareOutcome.SHARED_CHOOSER
    } catch (_: Exception) {
      ShareOutcome.FAILED
    }
  }
}
