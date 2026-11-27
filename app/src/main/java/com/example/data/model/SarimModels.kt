package com.example.data.model

enum class AppThemeMode(val label: String) {
  DARK("THEME: DARK"),
  AUTO("THEME: AUTO"),
  LIGHT("THEME: LIGHT"),
}

enum class HapticIntensity(val label: String, val description: String) {
  DISABLED("Disabled", "No tactile vibration feedback"),
  LIGHT("Light", "Subtle gentle tap"),
  MEDIUM("Medium", "Balanced standard tactile click"),
  STRONG("Strong", "Pronounced firm feedback"),
  EXTREME("Extreme", "Maximum intensity pulse"),
}

enum class SoundIntensity(val label: String, val description: String) {
  MUTED("Muted", "Silent — no audio cues"),
  SOFT("Soft (Discreet)", "Quiet subtle click"),
  STANDARD("Standard (Crisp)", "Balanced standard click"),
  LOUD("Loud (Resonant)", "Clear loud confirmation"),
  EXTREME("Extreme Boost (Maximum)", "Maximum volume alert tone"),
}

enum class UserRole(val label: String) {
  ADMIN("Admin"),
  REP("Rep"),
  PRODUCTION("Production"),
  FACTORY("Factory"),
  PAYMENTS("Payments"),
  SALES("Sales"),
}

enum class MainTab(val title: String) {
  OVERVIEW("Overview"),
  PRODUCTION("Production"),
  SALES("Sales"),
  CALCULATOR("Calculator"),
  FACTORY("Factory"),
  PAYMENTS("Payments"),
  REP_SALES("Rep Sales"),
}

sealed interface StandaloneScreen {
  data object Settings : StandaloneScreen
  data object Approvals : StandaloneScreen
  data object DailyLedger : StandaloneScreen
  data object StockTransfer : StandaloneScreen
  data object PaymentTransfer : StandaloneScreen
  data class AddEntity(val entityId: String? = null) : StandaloneScreen
  data class AddRawMaterial(val materialId: String? = null) : StandaloneScreen
  data object FormulaStore : StandaloneScreen
  data class FormulaEdit(val formulaId: String? = null) : StandaloneScreen
  data class Batching(val savedId: String? = null) : StandaloneScreen
  data class BatchCorrection(val savedId: String? = null) : StandaloneScreen
  data object BackupRestore : StandaloneScreen
  data object RecycleBin : StandaloneScreen
  data object DatabaseStructure : StandaloneScreen
  data object SoundHaptics : StandaloneScreen
  data object AppAccounts : StandaloneScreen
  data object StoreManager : StandaloneScreen
  data object CloseFinancialYear : StandaloneScreen
  data class CustomerDetails(val customerId: String) : StandaloneScreen
  data class CustomerEdit(val customerId: String? = null) : StandaloneScreen
  data class RepCustomerDetails(val customerId: String) : StandaloneScreen
  data class RepCustomerEdit(val customerId: String? = null) : StandaloneScreen
  data class EntityDetails(val entityId: String) : StandaloneScreen
  data class ExpenseDetails(val expenseName: String) : StandaloneScreen
}

enum class TimePeriod(val label: String) {
  DAILY("Daily"),
  WEEKLY("Weekly"),
  MONTHLY("Monthly"),
  YEARLY("Yearly"),
  ALL_TIME("All Time"),
}

enum class OverviewRange(val label: String, val months: Int) {
  M3("3M", 3),
  M6("6M", 6),
  M12("12M", 12),
}

enum class OverviewPanel(val label: String) {
  SALES("Sales"),
  PRODUCTION("Production"),
  MONEY("Money"),
  STOCK("Stock"),
  CHECKS("Checks"),
  ACTIVITY("Activity"),
}

data class FormulaIngredient(
  val refType: String = "MATERIAL", // MATERIAL or FORMULA
  val refId: String = "",
  val refName: String = "",
  val qtyKg: Double = 0.0,
)

data class LineItemBreakdown(
  val category: String = "",
  val amount: Double = 0.0,
)

data class EditHistoryEntry(
  val timestamp: Long = System.currentTimeMillis(),
  val summary: String = "",
)

data class AuditFinding(
  val id: String,
  val severity: String, // ERROR or WARNING
  val recordType: String, // SALE, PRODUCTION, MATERIAL, LEDGER
  val recordId: String,
  val dateEpoch: Long,
  val title: String,
  val reasons: List<String>,
  val note: String,
  val suggestedFieldLabel: String,
  val currentFieldValue: String,
)

data class ChartSeries(
  val label: String,
  val values: List<Double>,
  val colorHex: Long,
)

data class RankedBarItem(
  val label: String,
  val value: Double,
  val secondaryText: String = "",
  val badge: String = "",
)

data class MonthlySummaryRow(
  val monthLabel: String,
  val monthStartEpoch: Long,
  val producedKg: Double,
  val salesValue: Double,
  val netProfit: Double,
  val salesProfit: Double,
  val cashIn: Double,
  val cashOut: Double,
  val expenses: Double,
)
