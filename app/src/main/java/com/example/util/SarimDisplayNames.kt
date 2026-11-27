package com.example.util

import com.example.data.local.FormulaEntity
import com.example.data.local.JsonCodecs
import com.example.data.local.RawMaterialEntity

/** Turns internal record ids into human-readable names for anything shown on screen. */
object SarimDisplayNames {
  private val UUID_RE = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
  private val PREFIXED_ID_RE = Regex("^[a-zA-Z]{2,12}_[a-zA-Z0-9]{8,}$")

  /** True when [text] is blank or looks like an internal id (uuid, `custom_xxx`, `prefix_hash`). */
  fun looksLikeId(text: String?): Boolean {
    val t = text?.trim().orEmpty()
    if (t.isEmpty()) return true
    return UUID_RE.matches(t) || (PREFIXED_ID_RE.matches(t) && t.none { it == ' ' })
  }

  /**
   * Resolve a raw material's display name from its id: live material list first,
   * then the ingredient name stored in any formula, then the stored name if it is readable.
   */
  fun materialName(
    id: String,
    storedName: String?,
    materials: List<RawMaterialEntity>,
    formulas: List<FormulaEntity>,
  ): String {
    materials.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() }?.let { return it }
    if (!looksLikeId(storedName)) return storedName!!.trim()
    for (f in formulas) {
      JsonCodecs.decodeIngredients(f.ingredientsJson)
        .firstOrNull { it.refId == id && !looksLikeId(it.refName) }
        ?.let { return it.refName.trim() }
    }
    return "Unknown material"
  }
}
