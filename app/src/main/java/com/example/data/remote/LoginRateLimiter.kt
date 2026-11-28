package com.example.data.remote

/**
 * Port of the web `LoginRateLimiter` (`modules/sync.js`): 5 failed password sign-ins lock sign-in for
 * 15 minutes, and between attempts the next try is delayed 1s, 2s, 4s … up to 8s. The web keeps this in
 * `sessionStorage`, so it resets when the session ends; an in-memory object gives the same lifetime here.
 */
object LoginRateLimiter {
  const val MAX_ATTEMPTS = 5
  const val LOCKOUT_MS = 15L * 60L * 1000L
  private const val BACKOFF_BASE_MS = 1_000L
  private const val BACKOFF_CAP_MS = 8_000L

  @Volatile private var attempts = 0
  @Volatile private var lockedUntil = 0L

  fun isLockedOut(now: Long = System.currentTimeMillis()): Boolean = lockedUntil > now

  fun lockoutRemainingMs(now: Long = System.currentTimeMillis()): Long = maxOf(0L, lockedUntil - now)

  fun backoffMs(): Long {
    val n = attempts
    return if (n > 0) minOf(BACKOFF_BASE_MS * (1L shl (n - 1).coerceAtMost(20)), BACKOFF_CAP_MS) else 0L
  }

  @Synchronized
  fun recordFailure(now: Long = System.currentTimeMillis()) {
    attempts += 1
    if (attempts >= MAX_ATTEMPTS) lockedUntil = now + LOCKOUT_MS
  }

  @Synchronized
  fun recordSuccess() {
    attempts = 0
    lockedUntil = 0L
  }

  /** "Too many failed attempts. Try again in 14m 59s." (web wording). */
  fun lockoutMessage(accountLocked: Boolean = false): String {
    val sec = (lockoutRemainingMs() + 999L) / 1000L
    val prefix = if (accountLocked) "Too many failed attempts. Account temporarily locked." else "Too many failed attempts."
    return "$prefix Try again in ${sec / 60}m ${(sec % 60).toString().padStart(2, '0')}s."
  }
}
