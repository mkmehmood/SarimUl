package com.example

import com.example.data.backup.BackupCryptoException
import com.example.data.backup.SarimBackupCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SarimBackupCryptoTest {
  private val email = "Test@Example.com "
  private val password = "pässword✓"
  private val uid = "uid-123"

  private fun fixture(): ByteArray =
    javaClass.classLoader!!.getResourceAsStream("web_v4_fixture.gznd")!!.use { it.readBytes() }

  @Test fun decryptsAFileMadeByTheWebApp() {
    val plain = String(SarimBackupCrypto.decrypt(fixture(), email, password, uid), Charsets.UTF_8)
    assertTrue(plain.contains("\"hello\":\"wörld\""))
    assertTrue(plain.contains("\"version\":4"))
  }

  @Test fun roundTripsAndKeepsTheV4Header() {
    val enc = SarimBackupCrypto.encrypt("""{"a":1}""".toByteArray(), email, password, uid)
    assertTrue(SarimBackupCrypto.looksEncrypted(enc))
    assertEquals("""{"a":1}""", String(SarimBackupCrypto.decrypt(enc, email, password, uid)))
  }

  @Test fun reportsTheSameErrorCodesAsTheWebApp() {
    fun code(block: () -> Unit): String = try { block(); "NONE" } catch (e: BackupCryptoException) { e.code }
    assertEquals("WRONG_CREDENTIALS", code { SarimBackupCrypto.decrypt(fixture(), email, "nope", uid) })
    assertEquals("WRONG_ACCOUNT", code { SarimBackupCrypto.decrypt(fixture(), email, password, "someone-else") })
    assertEquals("INVALID_FORMAT", code { SarimBackupCrypto.decrypt("junk".toByteArray(), email, password, uid) })
  }

  @Test fun emailIsCaseAndSpaceInsensitiveLikeTheWeb() {
    val enc = SarimBackupCrypto.encrypt("x".toByteArray(), "A@B.com", password, uid)
    assertEquals("x", String(SarimBackupCrypto.decrypt(enc, "  a@b.COM ", password, uid)))
    try { SarimBackupCrypto.decrypt(enc, "a@b.com", password + "x", uid); fail("should not decrypt") } catch (_: BackupCryptoException) {}
  }
}
