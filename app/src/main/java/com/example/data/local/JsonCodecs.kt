package com.example.data.local

import com.example.data.model.EditHistoryChange
import com.example.data.model.EditHistoryEntry
import com.example.data.model.FormulaIngredient
import com.example.data.model.LineItemBreakdown

object JsonCodecs {
  fun encodeIngredients(list: List<FormulaIngredient>): String = encodeArray(
    list.map { item ->
      linkedMapOf<String, Any?>(
        "refType" to item.refType,
        "refId" to item.refId,
        "refName" to item.refName,
        "qtyKg" to item.qtyKg,
      )
    }
  )

  fun decodeIngredients(raw: String): List<FormulaIngredient> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
      (PureJsonParser(raw).parseValue() as? List<*>).orEmpty().mapNotNull { elem ->
        val obj = elem as? Map<*, *> ?: return@mapNotNull null
        FormulaIngredient(
          refType = obj["refType"]?.toString()?.ifBlank { null } ?: "MATERIAL",
          refId = obj["refId"]?.toString().orEmpty(),
          refName = obj["refName"]?.toString().orEmpty(),
          qtyKg = (obj["qtyKg"] as? Number)?.toDouble() ?: obj["qtyKg"]?.toString()?.toDoubleOrNull() ?: 0.0,
        )
      }
    }.getOrDefault(emptyList())
  }

  fun encodeStrings(list: List<String>): String = encodeArray(list)

  fun decodeStrings(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
      (PureJsonParser(raw).parseValue() as? List<*>).orEmpty()
        .mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
    }.getOrDefault(emptyList())
  }

  fun encodeLineItems(list: List<LineItemBreakdown>): String = encodeArray(
    list.map { item ->
      linkedMapOf<String, Any?>(
        "category" to item.category,
        "amount" to item.amount,
      )
    }
  )

  fun decodeLineItems(raw: String): List<LineItemBreakdown> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
      (PureJsonParser(raw).parseValue() as? List<*>).orEmpty().mapNotNull { elem ->
        val obj = elem as? Map<*, *> ?: return@mapNotNull null
        LineItemBreakdown(
          category = obj["category"]?.toString().orEmpty(),
          amount = (obj["amount"] as? Number)?.toDouble() ?: obj["amount"]?.toString()?.toDoubleOrNull() ?: 0.0,
        )
      }
    }.getOrDefault(emptyList())
  }

  fun encodeEditHistory(list: List<EditHistoryEntry>): String = encodeArray(
    list.map { item ->
      linkedMapOf<String, Any?>(
        "timestamp" to item.timestamp,
        "summary" to item.summary,
        "by" to item.by,
        "changes" to item.changes.map { c ->
          linkedMapOf<String, Any?>(
            "f" to c.fieldKey,
            "label" to c.label,
            "from" to c.from,
            "to" to c.to,
          )
        },
      )
    }
  )

  fun decodeEditHistory(raw: String): List<EditHistoryEntry> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
      (PureJsonParser(raw).parseValue() as? List<*>).orEmpty().mapNotNull { elem ->
        val obj = elem as? Map<*, *> ?: return@mapNotNull null
        val changesRaw = obj["changes"] as? List<*>
        val changes = changesRaw.orEmpty().mapNotNull { ch ->
          val cm = ch as? Map<*, *> ?: return@mapNotNull null
          EditHistoryChange(
            fieldKey = cm["f"]?.toString()?.ifBlank { null } ?: cm["fieldKey"]?.toString().orEmpty(),
            label = cm["label"]?.toString().orEmpty(),
            from = cm["from"]?.toString().orEmpty(),
            to = cm["to"]?.toString().orEmpty(),
          )
        }
        EditHistoryEntry(
          timestamp = (obj["timestamp"] as? Number)?.toLong() ?: obj["timestamp"]?.toString()?.toLongOrNull() ?: 0L,
          summary = obj["summary"]?.toString().orEmpty(),
          by = obj["by"]?.toString().orEmpty(),
          changes = changes,
        )
      }
    }.getOrDefault(emptyList())
  }

  data class FormulaMaterialUsed(
    val id: String,
    val name: String,
    val quantity: Double,
    val cost: Double,
  )

  fun encodeFormulaMaterialsUsed(list: List<FormulaMaterialUsed>): String = encodeArray(
    list.map { item ->
      linkedMapOf<String, Any?>(
        "id" to item.id,
        "name" to item.name,
        "quantity" to item.quantity,
        "cost" to item.cost,
      )
    }
  )

  fun decodeFormulaMaterialsUsed(raw: String): List<FormulaMaterialUsed> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
      (PureJsonParser(raw).parseValue() as? List<*>).orEmpty().mapNotNull { elem ->
        val obj = elem as? Map<*, *> ?: return@mapNotNull null
        FormulaMaterialUsed(
          id = obj["id"]?.toString().orEmpty(),
          name = obj["name"]?.toString().orEmpty(),
          quantity = (obj["quantity"] as? Number)?.toDouble() ?: obj["quantity"]?.toString()?.toDoubleOrNull() ?: 0.0,
          cost = (obj["cost"] as? Number)?.toDouble() ?: obj["cost"]?.toString()?.toDoubleOrNull() ?: 0.0,
        )
      }
    }.getOrDefault(emptyList())
  }

  data class AllocationItem(
    val saleId: String,
    val amount: Double,
    val full: Boolean,
  )

  fun encodeAllocations(list: List<AllocationItem>): String = encodeArray(
    list.map { item ->
      linkedMapOf<String, Any?>(
        "saleId" to item.saleId,
        "amount" to item.amount,
        "full" to item.full,
      )
    }
  )

  fun decodeAllocations(raw: String): List<AllocationItem> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
      (PureJsonParser(raw).parseValue() as? List<*>).orEmpty().mapNotNull { elem ->
        val obj = elem as? Map<*, *> ?: return@mapNotNull null
        AllocationItem(
          saleId = obj["saleId"]?.toString().orEmpty(),
          amount = (obj["amount"] as? Number)?.toDouble() ?: obj["amount"]?.toString()?.toDoubleOrNull() ?: 0.0,
          full = (obj["full"] as? Boolean) ?: (obj["full"]?.toString()?.equals("true", ignoreCase = true) == true),
        )
      }
    }.getOrDefault(emptyList())
  }

  data class CollectionAllocItem(
    val cid: String,
    val amount: Double,
    val full: Boolean,
  )

  fun encodeCollectionAllocs(list: List<CollectionAllocItem>): String = encodeArray(
    list.map { item ->
      linkedMapOf<String, Any?>(
        "cid" to item.cid,
        "amount" to item.amount,
        "full" to item.full,
      )
    }
  )

  fun decodeCollectionAllocs(raw: String): List<CollectionAllocItem> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
      (PureJsonParser(raw).parseValue() as? List<*>).orEmpty().mapNotNull { elem ->
        val obj = elem as? Map<*, *> ?: return@mapNotNull null
        CollectionAllocItem(
          cid = obj["cid"]?.toString().orEmpty(),
          amount = (obj["amount"] as? Number)?.toDouble() ?: obj["amount"]?.toString()?.toDoubleOrNull() ?: 0.0,
          full = (obj["full"] as? Boolean) ?: (obj["full"]?.toString()?.equals("true", ignoreCase = true) == true),
        )
      }
    }.getOrDefault(emptyList())
  }

  data class ConsumedCreditItem(
    val id: String,
    val amount: Double,
  )

  fun encodeConsumedCredits(list: List<ConsumedCreditItem>): String = encodeArray(
    list.map { item ->
      linkedMapOf<String, Any?>(
        "id" to item.id,
        "amount" to item.amount,
      )
    }
  )

  fun decodeConsumedCredits(raw: String): List<ConsumedCreditItem> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
      (PureJsonParser(raw).parseValue() as? List<*>).orEmpty().mapNotNull { elem ->
        when (elem) {
          is Map<*, *> -> {
            val id = elem["id"]?.toString()?.trim().orEmpty()
            if (id.isNotBlank()) {
              ConsumedCreditItem(
                id = id,
                amount = (elem["amount"] as? Number)?.toDouble()
                  ?: elem["amount"]?.toString()?.toDoubleOrNull()
                  ?: 0.0,
              )
            } else null
          }
          is String -> elem.trim().takeIf { it.isNotBlank() }?.let { ConsumedCreditItem(id = it, amount = 0.0) }
          else -> null
        }
      }
    }.getOrDefault(emptyList())
  }

  fun encodeLedgerLink(map: Map<String, Any?>?): String {
    if (map.isNullOrEmpty()) return ""
    val filtered = map.filterValues { it != null }
    if (filtered.isEmpty()) return ""
    return encodeObject(filtered)
  }

  fun decodeLedgerLink(raw: String): Map<String, Any?>? {
    if (raw.isBlank()) return null
    return runCatching {
      @Suppress("UNCHECKED_CAST")
      val parsed = PureJsonParser(raw).parseValue() as? Map<String, Any?> ?: return null
      parsed.filterValues { it != null }.takeIf { it.isNotEmpty() }
    }.getOrNull()
  }

  private fun encodeArray(items: List<*>): String =
    items.joinToString(",", prefix = "[", postfix = "]") { encodeValue(it) }

  fun encodeMap(map: Map<*, *>): String = encodeObject(map.filterValues { it != null })

  private fun encodeObject(map: Map<*, *>): String = buildString {
    append('{')
    var first = true
    for ((k, v) in map) {
      if (k == null || v == null) continue
      if (!first) append(',')
      first = false
      append('"').append(escapeJson(k.toString())).append("\":")
      append(encodeValue(v))
    }
    append('}')
  }

  private fun encodeValue(v: Any?): String = when (v) {
    null -> "null"
    is Boolean -> v.toString()
    is Number -> {
      val d = v.toDouble()
      if (!d.isFinite()) "0" else v.toString()
    }
    is Map<*, *> -> encodeObject(v)
    is Iterable<*> -> encodeArray(v.toList())
    is Array<*> -> encodeArray(v.toList())
    else -> "\"${escapeJson(v.toString())}\""
  }

  private fun escapeJson(value: String): String = buildString(value.length + 4) {
    for (c in value) {
      when (c) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        '\b' -> append("\\b")
        '\u000C' -> append("\\f")
        else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
      }
    }
  }
}

private class PureJsonParser(private val input: String) {
  private var pos = 0

  fun parseValue(): Any? {
    skipWs()
    if (pos >= input.length) return null
    return when (input[pos]) {
      '{' -> parseObject()
      '[' -> parseArray()
      '"' -> parseString()
      't', 'f' -> parseBoolean()
      'n' -> parseNull()
      else -> parseNumber()
    }
  }

  private fun parseObject(): Map<String, Any?> {
    pos++ // '{'
    val map = linkedMapOf<String, Any?>()
    skipWs()
    if (pos < input.length && input[pos] == '}') {
      pos++
      return map
    }
    while (pos < input.length) {
      skipWs()
      val key = parseString()
      skipWs()
      if (pos >= input.length || input[pos] != ':') throw IllegalArgumentException("Expected ':'")
      pos++
      map[key] = parseValue()
      skipWs()
      if (pos < input.length && input[pos] == ',') {
        pos++
      } else if (pos < input.length && input[pos] == '}') {
        pos++
        return map
      } else {
        throw IllegalArgumentException("Malformed object")
      }
    }
    throw IllegalArgumentException("Unterminated object")
  }

  private fun parseArray(): List<Any?> {
    pos++ // '['
    val list = mutableListOf<Any?>()
    skipWs()
    if (pos < input.length && input[pos] == ']') {
      pos++
      return list
    }
    while (pos < input.length) {
      list += parseValue()
      skipWs()
      if (pos < input.length && input[pos] == ',') {
        pos++
      } else if (pos < input.length && input[pos] == ']') {
        pos++
        return list
      } else {
        throw IllegalArgumentException("Malformed array")
      }
    }
    throw IllegalArgumentException("Unterminated array")
  }

  private fun parseString(): String {
    if (pos >= input.length || input[pos] != '"') throw IllegalArgumentException("Expected string")
    pos++
    val sb = StringBuilder()
    while (pos < input.length) {
      val c = input[pos++]
      when (c) {
        '"' -> return sb.toString()
        '\\' -> {
          if (pos >= input.length) throw IllegalArgumentException("Unterminated escape")
          when (val esc = input[pos++]) {
            '"', '\\', '/' -> sb.append(esc)
            'b' -> sb.append('\b')
            'f' -> sb.append('\u000C')
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            't' -> sb.append('\t')
            'u' -> {
              if (pos + 4 > input.length) throw IllegalArgumentException("Invalid unicode escape")
              val code = input.substring(pos, pos + 4).toInt(16)
              sb.append(code.toChar())
              pos += 4
            }
            else -> sb.append(esc)
          }
        }
        else -> sb.append(c)
      }
    }
    throw IllegalArgumentException("Unterminated string")
  }

  private fun parseBoolean(): Boolean {
    return if (input.startsWith("true", pos)) {
      pos += 4
      true
    } else if (input.startsWith("false", pos)) {
      pos += 5
      false
    } else {
      throw IllegalArgumentException("Invalid boolean")
    }
  }

  private fun parseNull(): Any? {
    if (input.startsWith("null", pos)) {
      pos += 4
      return null
    }
    throw IllegalArgumentException("Invalid null")
  }

  private fun parseNumber(): Number {
    val start = pos
    while (pos < input.length && input[pos] in "0123456789+-.eE") pos++
    val raw = input.substring(start, pos)
    return if ('.' in raw || 'e' in raw || 'E' in raw) raw.toDouble() else raw.toLong()
  }

  private fun skipWs() {
    while (pos < input.length && input[pos].isWhitespace()) pos++
  }
}
