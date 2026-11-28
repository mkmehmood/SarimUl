package com.example.data.backup

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Error codes match the web `CryptoEngine`: WRONG_ACCOUNT, WRONG_CREDENTIALS, INVALID_FORMAT. */
class BackupCryptoException(val code: String) : Exception(code)

/**
 * `.gznd` v4 container, byte-compatible with the web app's `CryptoEngine` (modules/business.js):
 *
 *   "GZND_ENC_V4" (11) | SHA-256(uid) (32) | salt (32) | iv (12) | AES-256-GCM ciphertext+tag
 *
 * Key = PBKDF2-HMAC-SHA512(email.lowercase().trim() + ":" + password + ":" + uid, salt, 210000 iterations, 256 bit).
 */
object SarimBackupCrypto {
  private val MAGIC_V4 = "GZND_ENC_V4".toByteArray(Charsets.US_ASCII)
  private const val SALT_LEN = 32
  private const val IV_LEN = 12
  private const val UID_HASH_LEN = 32
  private const val PBKDF2_ITERS = 210_000
  private const val KEY_BYTES = 32
  private val random = SecureRandom()

  fun encrypt(plaintext: ByteArray, email: String, password: String, uid: String): ByteArray {
    val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
    val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
    return encryptWith(plaintext, email, password, uid, salt, iv)
  }

  internal fun encryptWith(plaintext: ByteArray, email: String, password: String, uid: String, salt: ByteArray, iv: ByteArray): ByteArray {
    val key = deriveKey(email, password, uid, salt)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    val ct = cipher.doFinal(plaintext)
    return MAGIC_V4 + uidHash(uid) + salt + iv + ct
  }

  fun decrypt(file: ByteArray, email: String, password: String, uid: String): ByteArray {
    val magicLen = MAGIC_V4.size
    val minLen = magicLen + UID_HASH_LEN + SALT_LEN + IV_LEN + 16
    if (file.size < minLen || !file.copyOfRange(0, magicLen).contentEquals(MAGIC_V4)) {
      throw BackupCryptoException("INVALID_FORMAT")
    }
    var off = magicLen
    val storedUidHash = file.copyOfRange(off, off + UID_HASH_LEN); off += UID_HASH_LEN
    if (!MessageDigest.isEqual(storedUidHash, uidHash(uid))) throw BackupCryptoException("WRONG_ACCOUNT")
    val salt = file.copyOfRange(off, off + SALT_LEN); off += SALT_LEN
    val iv = file.copyOfRange(off, off + IV_LEN); off += IV_LEN
    val ct = file.copyOfRange(off, file.size)
    val key = deriveKey(email, password, uid, salt)
    return try {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
      cipher.doFinal(ct)
    } catch (_: Exception) {
      throw BackupCryptoException("WRONG_CREDENTIALS")
    }
  }

  /** True when [file] starts with the v4 header (used to tell .gznd from legacy plain JSON). */
  fun looksEncrypted(file: ByteArray): Boolean =
    file.size >= MAGIC_V4.size && file.copyOfRange(0, MAGIC_V4.size).contentEquals(MAGIC_V4)

  fun userMessage(e: BackupCryptoException): String = when (e.code) {
    "WRONG_ACCOUNT" -> "This backup belongs to a different account and cannot be restored here."
    "WRONG_CREDENTIALS" -> "Incorrect password. Decryption failed."
    "INVALID_FORMAT" -> "This file is not a valid encrypted backup."
    else -> "Decryption failed."
  }

  private fun uidHash(uid: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))

  private fun deriveKey(email: String, password: String, uid: String, salt: ByteArray): ByteArray {
    val ikm = (email.lowercase().trim() + ":" + password + ":" + uid).toByteArray(Charsets.UTF_8)
    return pbkdf2HmacSha512(ikm, salt, PBKDF2_ITERS, KEY_BYTES)
  }

  /** Straight PBKDF2 over raw bytes so the result is identical to WebCrypto on every JVM / Android build. */
  internal fun pbkdf2HmacSha512(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
    val mac = Mac.getInstance("HmacSHA512")
    mac.init(SecretKeySpec(password, "HmacSHA512"))
    val hLen = mac.macLength
    val blocks = (dkLen + hLen - 1) / hLen
    val out = ByteArray(blocks * hLen)
    for (i in 1..blocks) {
      mac.update(salt)
      mac.update(byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
      var u = mac.doFinal()
      val t = u.copyOf()
      for (j in 1 until iterations) {
        u = mac.doFinal(u)
        for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
      }
      System.arraycopy(t, 0, out, (i - 1) * hLen, hLen)
    }
    return out.copyOf(dkLen)
  }
}
