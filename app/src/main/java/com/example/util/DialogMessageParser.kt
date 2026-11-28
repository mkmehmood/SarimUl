package com.example.util

/** One "Label: value" pair of a dialog body (web `facts` item). */
data class DialogFact(val key: String, val value: String)

/** Parsed dialog body block; mirrors `parseMessage()` in the web app's modules/dialog.js. */
sealed interface DialogBlock {
  data class Paragraph(val text: String, val lead: Boolean) : DialogBlock
  data class Subhead(val text: String) : DialogBlock
  data class Facts(val items: List<DialogFact>) : DialogBlock
  data class Points(val items: List<String>) : DialogBlock
  data class Callout(val kind: CalloutKind, val label: String, val text: String) : DialogBlock
}

enum class CalloutKind { WARNING, DANGER }

data class ParsedDialogMessage(val blocks: List<DialogBlock>, val figure: DialogFact?)

object DialogMessageParser {
  private val leadingGlyphs = Regex("^[\\s\\u00A0\\u2190-\\u21FF\\u2600-\\u27BF\\uFE0F\\x{1F300}-\\x{1FAFF}]+")
  private val bulletRe = Regex("^[\\u2022\\u00B7\\u25CF*\\-\\u2013]\\s+(.+)$")
  private val noteRe = Regex("^(warning|note|caution|important):\\s+(.+)$", RegexOption.IGNORE_CASE)
  private val factRe = Regex("^([A-Za-z0-9][A-Za-z0-9 ()/&'.\\u2019-]{0,32}):\\s+(.{1,70})$")
  private val undoneRe = Regex("^this (action )?cannot be undone\\.?$", RegexOption.IGNORE_CASE)
  private val figurePriority = listOf(
    "^new total$", "^total( value| sold)?$", "^amount$", "^total cost$", "^required", "^overpayment$",
    "^collecting$", "^this credit sale$", "^credit sales?$", "^net profit$",
  ).map { Regex(it, RegexOption.IGNORE_CASE) }

  /** Web `WARN_RE`: titles that should be shown with the warning tone. */
  val warnRe = Regex(
    "warning|insufficient|exceed|over-?collect|overpay|high credit|caution|unsaved|mismatch|cannot|can't|" +
      "not enough|shortage|short by|already (used|sold|has)|outstanding",
    RegexOption.IGNORE_CASE,
  )

  /** Web `DELETE_RE`: danger dialogs about removing things get the trash icon. */
  val deleteRe = Regex("delete|remove|erase|purge|discard|clear|wipe", RegexOption.IGNORE_CASE)

  fun parse(message: String): ParsedDialogMessage {
    val lines = message.replace("\r", "").split("\n").map { it.trim { c -> c.isWhitespace() || c == '\u00A0' } }
    val blocks = mutableListOf<DialogBlock>()
    var facts: MutableList<DialogFact>? = null
    var points: MutableList<String>? = null
    var paragraphs = 0

    fun flush() {
      val f = facts
      if (f != null) blocks.add(DialogBlock.Facts(f))
      facts = null
      val p = points
      if (p != null) blocks.add(DialogBlock.Points(p))
      points = null
    }

    for (raw in lines) {
      if (raw.isEmpty()) { flush(); continue }
      if (raw.startsWith("\u26A0")) {
        flush()
        blocks += DialogBlock.Callout(CalloutKind.WARNING, "Warning", raw.replace(leadingGlyphs, ""))
        continue
      }
      // Web drops "info" notes (↩ / ℹ lines and "Note:" lines) from the dialog body.
      if (raw.startsWith("\u21A9") || raw.startsWith("\u2139")) { flush(); continue }
      val line = raw.replace(leadingGlyphs, "").ifEmpty { raw }
      val bullet = bulletRe.find(line)
      if (bullet != null) {
        if (facts != null) flush()
        val list = points ?: mutableListOf<String>()
        list.add(bullet.groupValues[1])
        points = list
        continue
      }
      if (undoneRe.matches(line)) {
        flush()
        blocks += DialogBlock.Callout(CalloutKind.DANGER, "Permanent", line)
        continue
      }
      val note = noteRe.find(line)
      if (note != null) {
        flush()
        val kind = note.groupValues[1].lowercase()
        if (kind != "note" && kind != "important") {
          blocks += DialogBlock.Callout(CalloutKind.WARNING, kind.replaceFirstChar { it.uppercase() }, note.groupValues[2])
        }
        continue
      }
      val fact = factRe.find(line)
      if (fact != null && !fact.groupValues[1].equals("http", true) && !fact.groupValues[1].equals("https", true)) {
        if (points != null) flush()
        val list = facts ?: mutableListOf<DialogFact>()
        list.add(DialogFact(fact.groupValues[1], fact.groupValues[2]))
        facts = list
        continue
      }
      flush()
      if (line.endsWith(":")) { blocks += DialogBlock.Subhead(line); continue }
      blocks += DialogBlock.Paragraph(line, lead = paragraphs == 0 && blocks.isEmpty())
      paragraphs += 1
    }
    flush()

    var figure: DialogFact? = null
    val mutable = blocks.toMutableList()
    outer@ for (re in figurePriority) {
      for ((bi, blk) in mutable.withIndex()) {
        if (blk !is DialogBlock.Facts) continue
        val idx = blk.items.indexOfFirst { re.containsMatchIn(it.key) && it.value.any(Char::isDigit) && it.value.length <= 18 }
        if (idx >= 0) {
          figure = blk.items[idx]
          mutable[bi] = DialogBlock.Facts(blk.items.filterIndexed { i, _ -> i != idx })
          break@outer
        }
      }
    }
    return ParsedDialogMessage(mutable.filter { it !is DialogBlock.Facts || it.items.isNotEmpty() }, figure)
  }
}
