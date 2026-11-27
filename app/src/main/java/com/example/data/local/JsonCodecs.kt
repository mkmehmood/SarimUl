package com.example.data.local

import com.example.data.model.EditHistoryEntry
import com.example.data.model.FormulaIngredient
import com.example.data.model.LineItemBreakdown
import org.json.JSONArray
import org.json.JSONObject

object JsonCodecs {
  fun encodeIngredients(list: List<FormulaIngredient>): String {
    val arr = JSONArray()
    list.forEach { item ->
      arr.put(
        JSONObject().apply {
          put("refType", item.refType)
          put("refId", item.refId)
          put("refName", item.refName)
          put("qtyKg", item.qtyKg)
        }
      )
    }
    return arr.toString()
  }

  fun decodeIngredients(raw: String): List<FormulaIngredient> {
    if (raw.isBlank()) return emptyList()
    return try {
      val arr = JSONArray(raw)
      (0 until arr.length()).map { i ->
        val obj = arr.getJSONObject(i)
        FormulaIngredient(
          refType = obj.optString("refType", "MATERIAL"),
          refId = obj.optString("refId", ""),
          refName = obj.optString("refName", ""),
          qtyKg = obj.optDouble("qtyKg", 0.0),
        )
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  fun encodeStrings(list: List<String>): String {
    val arr = JSONArray()
    list.forEach { arr.put(it) }
    return arr.toString()
  }

  fun decodeStrings(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return try {
      val arr = JSONArray(raw)
      (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    } catch (_: Exception) {
      emptyList()
    }
  }

  fun encodeLineItems(list: List<LineItemBreakdown>): String {
    val arr = JSONArray()
    list.forEach { item ->
      arr.put(
        JSONObject().apply {
          put("category", item.category)
          put("amount", item.amount)
        }
      )
    }
    return arr.toString()
  }

  fun decodeLineItems(raw: String): List<LineItemBreakdown> {
    if (raw.isBlank()) return emptyList()
    return try {
      val arr = JSONArray(raw)
      (0 until arr.length()).map { i ->
        val obj = arr.getJSONObject(i)
        LineItemBreakdown(
          category = obj.optString("category", ""),
          amount = obj.optDouble("amount", 0.0),
        )
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  fun encodeEditHistory(list: List<EditHistoryEntry>): String {
    val arr = JSONArray()
    list.forEach { item ->
      arr.put(
        JSONObject().apply {
          put("timestamp", item.timestamp)
          put("summary", item.summary)
        }
      )
    }
    return arr.toString()
  }

  fun decodeEditHistory(raw: String): List<EditHistoryEntry> {
    if (raw.isBlank()) return emptyList()
    return try {
      val arr = JSONArray(raw)
      (0 until arr.length()).map { i ->
        val obj = arr.getJSONObject(i)
        EditHistoryEntry(
          timestamp = obj.optLong("timestamp", 0L),
          summary = obj.optString("summary", ""),
        )
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  data class FormulaMaterialUsed(
    val id: String,
    val name: String,
    val quantity: Double,
    val cost: Double,
  )

  fun encodeFormulaMaterialsUsed(list: List<FormulaMaterialUsed>): String {
    val arr = JSONArray()
    list.forEach { item ->
      arr.put(
        JSONObject().apply {
          put("id", item.id)
          put("name", item.name)
          put("quantity", item.quantity)
          put("cost", item.cost)
        }
      )
    }
    return arr.toString()
  }

  fun decodeFormulaMaterialsUsed(raw: String): List<FormulaMaterialUsed> {
    if (raw.isBlank()) return emptyList()
    return try {
      val arr = JSONArray(raw)
      (0 until arr.length()).map { i ->
        val obj = arr.getJSONObject(i)
        FormulaMaterialUsed(
          id = obj.optString("id", ""),
          name = obj.optString("name", ""),
          quantity = obj.optDouble("quantity", 0.0),
          cost = obj.optDouble("cost", 0.0),
        )
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  data class AllocationItem(
    val saleId: String,
    val amount: Double,
    val full: Boolean,
  )

  fun encodeAllocations(list: List<AllocationItem>): String {
    val arr = JSONArray()
    list.forEach { item ->
      arr.put(
        JSONObject().apply {
          put("saleId", item.saleId)
          put("amount", item.amount)
          put("full", item.full)
        }
      )
    }
    return arr.toString()
  }

  fun decodeAllocations(raw: String): List<AllocationItem> {
    if (raw.isBlank()) return emptyList()
    return try {
      val arr = JSONArray(raw)
      (0 until arr.length()).map { i ->
        val obj = arr.getJSONObject(i)
        AllocationItem(
          saleId = obj.optString("saleId", ""),
          amount = obj.optDouble("amount", 0.0),
          full = obj.optBoolean("full", false),
        )
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  data class CollectionAllocItem(
    val cid: String,
    val amount: Double,
    val full: Boolean,
  )

  fun encodeCollectionAllocs(list: List<CollectionAllocItem>): String {
    val arr = JSONArray()
    list.forEach { item ->
      arr.put(
        JSONObject().apply {
          put("cid", item.cid)
          put("amount", item.amount)
          put("full", item.full)
        }
      )
    }
    return arr.toString()
  }

  fun decodeCollectionAllocs(raw: String): List<CollectionAllocItem> {
    if (raw.isBlank()) return emptyList()
    return try {
      val arr = JSONArray(raw)
      (0 until arr.length()).map { i ->
        val obj = arr.getJSONObject(i)
        CollectionAllocItem(
          cid = obj.optString("cid", ""),
          amount = obj.optDouble("amount", 0.0),
          full = obj.optBoolean("full", false),
        )
      }
    } catch (_: Exception) {
      emptyList()
    }
  }

  fun encodeLedgerLink(map: Map<String, Any?>?): String {
    if (map.isNullOrEmpty()) return ""
    return JSONObject(map.filterValues { it != null }).toString()
  }

  fun decodeLedgerLink(raw: String): Map<String, Any?>? {
    if (raw.isBlank()) return null
    return try {
      val obj = JSONObject(raw)
      val out = linkedMapOf<String, Any?>()
      val keys = obj.keys()
      while (keys.hasNext()) {
        val k = keys.next()
        val v = obj.opt(k)
        if (v != null && v != JSONObject.NULL) out[k] = v
      }
      out.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
      null
    }
  }
}
