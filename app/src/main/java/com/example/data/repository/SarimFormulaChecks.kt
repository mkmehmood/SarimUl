package com.example.data.repository

/** Port of web `modules/formula-checks.js` plus the name / duplicate helpers the web editor uses. */
object SarimFormulaChecks {
  /** One editor row. [linkedId] is the picked material / formula id ("" when nothing was picked). */
  data class Row(
    val label: String,
    val qtyText: String,
    val linkedId: String,
    val isFormula: Boolean,
  )

  private fun clean(v: String): String = v.replace(Regex("\\s+"), " ").trim()

  /** Returns the first problem in the rows, or "" when the rows are fine. Same wording as web. */
  fun formIssue(rows: List<Row>): String {
    val seen = mutableSetOf<String>()
    for (r in rows) {
      val label = clean(r.label)
      val hasQty = r.qtyText.trim().isNotEmpty()
      val qty = r.qtyText.trim().toDoubleOrNull() ?: Double.NaN
      if (label.isEmpty() && !hasQty) continue
      val linked = r.linkedId.isNotBlank()
      if (label.isNotEmpty() && !linked) return "Pick \"$label\" from the list, or clear that row."
      if (label.isEmpty() && hasQty) return "A row has a quantity but no ingredient. Pick an ingredient or clear the row."
      if (!(qty > 0.0)) return "Enter a quantity greater than 0 for $label, or clear that row."
      val id = if (r.isFormula) "f:${r.linkedId}" else "m:${r.linkedId}"
      if (!seen.add(id)) return "$label is listed twice. Combine it into one row."
    }
    return ""
  }

  /** Web `duplicateName`: whitespace-collapsed, case-insensitive match against other formulas. */
  fun duplicateName(typed: String, known: List<Pair<String, String>>, ignoreId: String?): String? {
    val key = clean(typed).lowercase()
    if (key.isEmpty()) return null
    return known.firstOrNull { (id, name) -> id != ignoreId && clean(name).lowercase() == key }?.second
  }
}
