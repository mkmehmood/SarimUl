package com.example.data.model

import com.example.data.local.*

data class AccountSessionInfo(
  val uid: String = "",
  val email: String = "",
  val name: String = "",
  val roles: List<String> = emptyList(),
  val ownerUid: String? = null,
  val legacy: Boolean = false,
  val approved: Boolean = true,
  val ts: Long = System.currentTimeMillis(),
) {
  fun toJson(): String {
    val rolesArr = RolesHelper.cleanRoles(roles).joinToString(",") { "\"${escapeJson(it)}\"" }
    val ownerJson = ownerUid?.takeIf { it.isNotBlank() }?.let { "\"${escapeJson(it)}\"" } ?: "null"
    return buildString {
      append('{')
      append("\"uid\":\"${escapeJson(uid)}\",")
      append("\"email\":\"${escapeJson(email.lowercase())}\",")
      append("\"name\":\"${escapeJson(name)}\",")
      append("\"roles\":[$rolesArr],")
      append("\"ownerUid\":$ownerJson,")
      append("\"legacy\":$legacy,")
      append("\"approved\":$approved,")
      append("\"ts\":$ts")
      append('}')
    }
  }

  companion object {
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

    fun fromJson(raw: String?): AccountSessionInfo? {
      if (raw.isNullOrBlank()) return null
      return runCatching {
        val parsed = SimpleJsonReader(raw).parseValue() as? Map<*, *> ?: return null
        val rolesList = (parsed["roles"] as? List<*>)?.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
          ?: return null
        val ownerRaw = parsed["ownerUid"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        AccountSessionInfo(
          uid = parsed["uid"]?.toString().orEmpty(),
          email = parsed["email"]?.toString().orEmpty().lowercase(),
          name = parsed["name"]?.toString().orEmpty(),
          roles = RolesHelper.cleanRoles(rolesList),
          ownerUid = ownerRaw,
          legacy = (parsed["legacy"] as? Boolean) ?: false,
          approved = (parsed["approved"] as? Boolean) ?: true,
          ts = (parsed["ts"] as? Number)?.toLong() ?: 0L,
        )
      }.getOrNull()
    }
  }
}

private class SimpleJsonReader(private val input: String) {
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
    return map
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
    return list
  }

  private fun parseString(): String {
    if (input[pos] != '"') throw IllegalArgumentException("Expected '\"'")
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
              val hex = input.substring(pos, (pos + 4).coerceAtMost(input.length))
              sb.append(hex.toInt(16).toChar())
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
      pos += 4; true
    } else if (input.startsWith("false", pos)) {
      pos += 5; false
    } else {
      throw IllegalArgumentException("Invalid boolean")
    }
  }

  private fun parseNull(): Any? {
    if (input.startsWith("null", pos)) {
      pos += 4; return null
    }
    throw IllegalArgumentException("Invalid null")
  }

  private fun parseNumber(): Number {
    val start = pos
    while (pos < input.length && input[pos] in "0123456789+-.eE") pos++
    val raw = input.substring(start, pos)
    return raw.toLongOrNull() ?: raw.toDouble()
  }

  private fun skipWs() {
    while (pos < input.length && input[pos].isWhitespace()) pos++
  }
}

data class DerivedAccountSession(
  val mode: String, // "admin" or "userrole"
  val tabs: List<MainTab>,
  val roles: List<String>,
)

data class RoleChangeCopy(
  val title: String,
  val confirmText: String,
  val danger: Boolean,
  val message: String,
)

data class RepRemovalPlan(
  val blocked: Boolean,
  val count: Int,
  val remove: Boolean,
)

/**
 * Pure Kotlin port of web repo `modules/roles.js` and `modules/rep-data.js`.
 */
object RolesHelper {
  val ROLE_KEYS: List<String> = listOf("admin", "rep", "production", "factory", "payments", "sales")

  val ROLE_LABELS: Map<String, String> = mapOf(
    "admin" to "Admin",
    "rep" to "Sales Rep",
    "production" to "Production",
    "factory" to "Factory",
    "payments" to "Payments",
    "sales" to "Sales",
  )

  val ROLE_ACCESS: Map<String, String> = mapOf(
    "admin" to "every tab, all money figures and account management",
    "rep" to "the Rep tab with their own customers and history, and a place in the rep list",
    "production" to "the Production tab (entry only)",
    "factory" to "the Factory tab (entry only)",
    "payments" to "the Payments tab for entering transactions; payment history and money figures stay hidden",
    "sales" to "the Sales tab; money figures stay hidden",
  )

  private val ROLE_TAB: Map<String, MainTab> = mapOf(
    "rep" to MainTab.REP_SALES,
    "production" to MainTab.PRODUCTION,
    "factory" to MainTab.FACTORY,
    "payments" to MainTab.PAYMENTS,
    "sales" to MainTab.SALES,
  )

  private val TAB_ORDER: List<MainTab> = listOf(
    MainTab.PRODUCTION,
    MainTab.FACTORY,
    MainTab.SALES,
    MainTab.PAYMENTS,
    MainTab.REP_SALES,
  )

  private val MONEY_TABS: Set<MainTab> = setOf(MainTab.PAYMENTS, MainTab.SALES)

  fun cleanRoles(list: Iterable<String>?): List<String> {
    if (list == null) return emptyList()
    val seen = hashSetOf<String>()
    for (raw in list) {
      val key = raw.trim().lowercase()
      if (key in ROLE_KEYS) seen.add(key)
    }
    return ROLE_KEYS.filter { it in seen }
  }

  fun normalizeRoles(userDoc: Map<String, Any?>?): List<String> {
    if (userDoc == null) return emptyList()
    val rolesField = userDoc["roles"]
    if (rolesField is List<*>) {
      return cleanRoles(rolesField.mapNotNull { it?.toString() })
    }
    val legacyRole = userDoc["role"]?.toString() ?: return emptyList()
    return cleanRoles(listOf(legacyRole))
  }

  fun toUserRoleSet(roleKeys: List<String>, legacy: Boolean = false): Set<UserRole> {
    val cleaned = cleanRoles(roleKeys)
    if (legacy && cleaned.isEmpty()) return setOf(UserRole.ADMIN)
    return cleaned.mapNotNull { key ->
      when (key) {
        "admin" -> UserRole.ADMIN
        "rep" -> UserRole.REP
        "production" -> UserRole.PRODUCTION
        "factory" -> UserRole.FACTORY
        "payments" -> UserRole.PAYMENTS
        "sales" -> UserRole.SALES
        else -> null
      }
    }.toSet()
  }

  fun fromUserRoleSet(roles: Set<UserRole>): List<String> =
    cleanRoles(roles.map { it.name.lowercase() })

  fun parseRolesCsv(csv: String): List<String> =
    cleanRoles(csv.split(",").map { it.trim() })

  fun hasRole(roles: List<String>, roleKey: String): Boolean =
    roleKey.lowercase() in cleanRoles(roles)

  fun toggleRole(roles: List<String>, roleKey: String): List<String> {
    val cur = cleanRoles(roles)
    val k = roleKey.trim().lowercase()
    if (k !in ROLE_KEYS) return cur
    return if (k in cur) cleanRoles(cur.filter { it != k }) else cleanRoles(cur + k)
  }

  fun tabsForRoles(roles: List<String>): List<MainTab> {
    val cur = cleanRoles(roles)
    val tabs = cur.mapNotNull { ROLE_TAB[it] }.toSet()
    return TAB_ORDER.filter { it in tabs }
  }

  fun rolesRestrictMoney(roles: List<String>): Boolean {
    val cur = cleanRoles(roles)
    if ("admin" in cur) return false
    return tabsForRoles(cur).any { it in MONEY_TABS }
  }

  fun deriveSession(roles: List<String>): DerivedAccountSession {
    val cur = cleanRoles(roles)
    if ("admin" in cur) {
      return DerivedAccountSession(mode = "admin", tabs = MainTab.entries, roles = cur)
    }
    val tabs = tabsForRoles(cur)
    val mode = if (cur.size == 1 && cur[0] in setOf("rep", "production", "factory")) {
      cur[0]
    } else {
      "userrole"
    }
    return DerivedAccountSession(mode = mode, tabs = tabs, roles = cur)
  }

  fun isWorkspaceMember(userDoc: Map<String, Any?>?, uid: String): Boolean {
    val owner = (userDoc?.get("ownerUid") as? String)?.trim().orEmpty()
    return owner.isNotBlank() && owner != uid
  }

  fun resolveOwnerUid(userDoc: Map<String, Any?>?, uid: String): String? =
    if (isWorkspaceMember(userDoc, uid)) (userDoc?.get("ownerUid") as? String)?.trim() else null

  fun isLegacyAccount(userDoc: Map<String, Any?>?, uid: String): Boolean =
    normalizeRoles(userDoc).isEmpty() && !isWorkspaceMember(userDoc, uid)

  fun canAdoptAccount(
    userDoc: Map<String, Any?>?,
    uid: String,
    workspaceUid: String,
    knownUids: Set<String>,
  ): Pair<Boolean, String?> {
    if (userDoc == null) return true to null
    if (uid == workspaceUid) return true to null
    val ownerUid = (userDoc["ownerUid"] as? String)?.trim().orEmpty()
    if (ownerUid.isNotEmpty()) {
      return if (ownerUid == workspaceUid) true to null else false to "external"
    }
    if (uid in knownUids) return true to null
    return false to "external"
  }

  fun roleNames(roles: List<String>): String =
    cleanRoles(roles).joinToString(", ") { ROLE_LABELS[it] ?: it }.ifBlank { "none" }

  fun describeRoleChange(
    name: String,
    isSelf: Boolean,
    roleKey: String,
    afterRoles: List<String>,
  ): RoleChangeCopy {
    val label = ROLE_LABELS[roleKey] ?: roleKey
    val access = ROLE_ACCESS[roleKey] ?: ""
    val cleanAfter = cleanRoles(afterRoles)
    val adding = roleKey in cleanAfter
    val now = roleNames(cleanAfter)
    val whenText = if (isSelf) {
      "Your session updates immediately to apply it."
    } else {
      "It takes effect on their device within seconds."
    }
    return if (adding) {
      RoleChangeCopy(
        title = "Assign $label Role",
        confirmText = "Assign $label",
        danger = roleKey == "admin",
        message = "Give $name the $label role?\n\nThey will get $access.\n\nRoles after this change: $now.\n\n$whenText",
      )
    } else {
      val none = cleanAfter.isEmpty()
      RoleChangeCopy(
        title = "Remove $label Role",
        confirmText = "Remove $label",
        danger = none || roleKey == "admin",
        message = "Remove the $label role from $name?\n\nThey will lose $access.\n\nRoles after this change: $now." +
          (if (none) "\n\nThey will have no access until another role is assigned." else "") +
          "\n\n$whenText",
      )
    }
  }

  fun accountInfoFromDoc(
    data: Map<String, Any?>?,
    uid: String,
    email: String,
    fallbackDisplayName: String = "",
  ): AccountSessionInfo {
    val d = data ?: emptyMap()
    val fallback = email.substringBefore("@")
    val name = (d["displayName"]?.toString()?.takeIf { it.isNotBlank() }
      ?: d["name"]?.toString()?.takeIf { it.isNotBlank() }
      ?: fallbackDisplayName.takeIf { it.isNotBlank() }
      ?: fallback).trim()
    return AccountSessionInfo(
      uid = uid,
      email = email.trim().lowercase(),
      name = name,
      roles = normalizeRoles(d),
      ownerUid = resolveOwnerUid(d, uid),
      legacy = isLegacyAccount(d, uid),
      approved = d["approved"] != false,
    )
  }

  // ---- Rep data & list helpers (modules/rep-data.js) ----

  fun repKey(name: String?): String = (name ?: "").trim().uppercase()

  fun withoutRep(list: List<String>, name: String?): List<String> {
    val target = repKey(name)
    if (target.isEmpty()) return list
    return list.filter { repKey(it) != target }
  }

  fun repDataCount(
    name: String?,
    sales: List<SaleTransactionEntity>,
    customers: List<CustomerEntity>,
    transactions: List<PaymentTransactionEntity>,
    expenses: List<ExpenseRecordEntity>,
    returns: List<ReturnLogEntity>,
  ): Int {
    val key = repKey(name)
    if (key.isEmpty()) return 0
    var count = 0
    count += sales.count { !it.isDeleted && repKey(it.repName) == key }
    count += customers.count { !it.isDeleted && it.isRepCustomer && repKey(it.repName) == key }
    count += returns.count { !it.isDeleted && repKey(it.seller) == key }
    // Transactions / expenses may carry salesRep in description/entityOrExpenseName when rep-scoped
    count += transactions.count { !it.isDeleted && repKey(it.entityOrExpenseName) == key }
    count += expenses.count { !it.isDeleted && repKey(it.name) == key }
    return count
  }

  fun repRemovalPlan(
    name: String?,
    repsList: List<String>,
    sales: List<SaleTransactionEntity>,
    customers: List<CustomerEntity>,
    transactions: List<PaymentTransactionEntity>,
    expenses: List<ExpenseRecordEntity>,
    returns: List<ReturnLogEntity>,
  ): RepRemovalPlan {
    val key = repKey(name)
    val count = repDataCount(name, sales, customers, transactions, expenses, returns)
    val listed = key.isNotEmpty() && repsList.any { repKey(it) == key }
    return RepRemovalPlan(blocked = count > 0, count = count, remove = count == 0 && listed)
  }
}
