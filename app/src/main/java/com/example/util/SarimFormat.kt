package com.example.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

object SarimFormat {
  private val dateDisplayFmt = ThreadLocal.withInitial { SimpleDateFormat("dd MMM yyyy", Locale.US) }
  private val dateInputFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
  private val monthYearFmt = ThreadLocal.withInitial { SimpleDateFormat("MMM yyyy", Locale.US) }
  private val weekdayFmt = ThreadLocal.withInitial { SimpleDateFormat("EEE", Locale.US) }
  private val timeFmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale.US) }
  private val clockFmt = ThreadLocal.withInitial { SimpleDateFormat("hh:mm a", Locale.US) }

  fun formatDate(epochMillis: Long): String {
    return dateDisplayFmt.get()!!.format(Date(epochMillis)).uppercase(Locale.US)
  }

  fun formatDateFriendly(epochMillis: Long): String {
    return dateDisplayFmt.get()!!.format(Date(epochMillis))
  }

  fun dateInput(epochMillis: Long): String {
    return dateInputFmt.get()!!.format(Date(epochMillis))
  }

  fun formatMonthYear(epochMillis: Long): String {
    return monthYearFmt.get()!!.format(Date(epochMillis)).uppercase(Locale.US)
  }

  fun formatDayNumber(epochMillis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
    return cal.get(Calendar.DAY_OF_MONTH).toString()
  }

  fun formatWeekday(epochMillis: Long): String {
    return weekdayFmt.get()!!.format(Date(epochMillis)).uppercase(Locale.US)
  }

  fun formatYear(epochMillis: Long): Int {
    val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
    return cal.get(Calendar.YEAR)
  }

  fun formatMonthKey(epochMillis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
    return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH) + 1}"
  }

  fun formatDayKey(epochMillis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = epochMillis }
    return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH) + 1}-${cal.get(Calendar.DAY_OF_MONTH)}"
  }

  fun formatTime(epochMillis: Long): String = timeFmt.get()!!.format(Date(epochMillis))

  fun formatClock(epochMillis: Long): String = clockFmt.get()!!.format(Date(epochMillis))

  fun startOfDay(epochMillis: Long): Long {
    val cal = Calendar.getInstance().apply {
      timeInMillis = epochMillis
      set(Calendar.HOUR_OF_DAY, 0)
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
  }

  fun endOfDay(epochMillis: Long): Long {
    val cal = Calendar.getInstance().apply {
      timeInMillis = epochMillis
      set(Calendar.HOUR_OF_DAY, 23)
      set(Calendar.MINUTE, 59)
      set(Calendar.SECOND, 59)
      set(Calendar.MILLISECOND, 999)
    }
    return cal.timeInMillis
  }

  fun daysAgo(days: Int): Long {
    val cal = Calendar.getInstance().apply {
      add(Calendar.DAY_OF_YEAR, -days)
      set(Calendar.HOUR_OF_DAY, 0)
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis
  }

  /**
   * Formats a number using South Asian digit grouping (e.g., 14,09,764 or 1,72,578.64).
   */
  fun formatSouthAsian(value: Double, decimals: Int = 2, trimTrailingZero: Boolean = true): String {
    if (value.isNaN() || value.isInfinite()) return "0"
    val negative = value < -0.0001
    val absVal = abs(value)
    val factor = when (decimals) {
      0 -> 1.0
      1 -> 10.0
      3 -> 1000.0
      else -> 100.0
    }
    val scaled = (absVal * factor).roundToLong()
    val intPart = scaled / factor.toLong()
    val fracPart = scaled % factor.toLong()

    val intStr = intPart.toString()
    val grouped = if (intStr.length <= 3) {
      intStr
    } else {
      val last3 = intStr.takeLast(3)
      val rest = intStr.dropLast(3)
      val restGrouped = rest.reversed().chunked(2).joinToString(",").reversed()
      "$restGrouped,$last3"
    }

    val fracStr = if (decimals == 0) {
      ""
    } else {
      val padded = fracPart.toString().padStart(decimals, '0')
      if (trimTrailingZero) {
        val trimmed = padded.trimEnd('0')
        if (trimmed.isEmpty()) "" else ".$trimmed"
      } else {
        ".$padded"
      }
    }
    return (if (negative) "-" else "") + grouped + fracStr
  }

  fun formatRs(value: Double, showSymbol: Boolean = true, privacyHidden: Boolean = false): String {
    if (privacyHidden) return if (showSymbol) "Rs ••••" else "••••"
    val formatted = formatSouthAsian(value, decimals = 2, trimTrailingZero = true)
    return if (showSymbol) "Rs $formatted" else formatted
  }

  fun formatKg(value: Double): String {
    return "${formatSouthAsian(value, decimals = 3, trimTrailingZero = true)} kg"
  }

  fun formatQty(value: Double, decimals: Int = 3): String {
    return formatSouthAsian(value, decimals = decimals, trimTrailingZero = true)
  }

  fun parseSafeDouble(raw: String): Double? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.contains('e', ignoreCase = true) || trimmed.contains(' ')) return null
    if (trimmed.count { it == '.' } > 1) return null
    return trimmed.toDoubleOrNull()
  }

  fun isValidPhone(phone: String): Boolean {
    val cleaned = phone.trim().replace("-", "").replace(" ", "")
    if (cleaned.isEmpty()) return false
    val digits = if (cleaned.startsWith("+")) cleaned.substring(1) else cleaned
    return digits.length in 7..15 && digits.all { it.isDigit() }
  }

  fun isFutureDate(epochMillis: Long): Boolean {
    return startOfDay(epochMillis) > startOfDay(System.currentTimeMillis())
  }

  fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
      parts.isEmpty() -> "?"
      parts.size == 1 -> parts[0].take(2).uppercase(Locale.US)
      else -> "${parts.first().first()}${parts.last().first()}".uppercase(Locale.US)
    }
  }
}
