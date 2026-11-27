package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.*
import com.example.data.repository.SarimRepository
import com.example.util.SarimFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Reusable base ViewModel injecting [SarimRepository] (Room DAO repository) and managing
 * immutable UI state updates and lifecycle-aware StateFlow observation.
 */
abstract class BaseFeatureViewModel<S>(
  protected val repository: SarimRepository,
  initialState: S,
) : ViewModel() {

  protected val _uiState = MutableStateFlow(initialState)
  val uiState: StateFlow<S> = _uiState.asStateFlow()

  protected fun updateState(reducer: (S) -> S) {
    _uiState.update(reducer)
  }

  protected fun <T> observeFlow(
    flow: Flow<T>,
    onError: (Throwable) -> Unit = {},
    onEachValue: (T) -> Unit,
  ) {
    viewModelScope.launch {
      flow
        .catch { err ->
          if (err is CancellationException) throw err
          onError(err)
        }
        .collect { value ->
          onEachValue(value)
        }
    }
  }

  protected fun launchRepoMutation(
    onError: (String) -> Unit,
    block: suspend () -> Unit,
  ) {
    viewModelScope.launch {
      try {
        block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        onError(e.localizedMessage ?: "Operation failed")
      }
    }
  }
}

// =====================================================================
// Ledger Feature Base ViewModel & State
// =====================================================================

data class LedgerUiState(
  val entries: List<DailyLedgerEntity> = emptyList(),
  val filteredEntries: List<DailyLedgerEntity> = emptyList(),
  val channelBalances: Map<String, Double> = emptyMap(),
  val knownChannels: List<String> = listOf("Cash", "Bank Transfer", "Easypaisa", "JazzCash"),
  val cashGroup: Double = 0.0,
  val bankGroup: Double = 0.0,
  val othersGroup: Double = 0.0,
  val totalCashInHand: Double = 0.0,
  val totalIn: Double = 0.0,
  val totalOut: Double = 0.0,
  val netBalance: Double = 0.0,
  val inCount: Int = 0,
  val outCount: Int = 0,
  val categorySummary: List<Pair<String, Double>> = emptyList(),
  val searchQuery: String = "",
  val typeFilter: String = "ALL", // ALL, IN, OUT, TRANSFER
  val isLoading: Boolean = true,
  val statusMessage: String? = null,
  val errorMessage: String? = null,
)

open class BaseLedgerViewModel(
  repository: SarimRepository,
) : BaseFeatureViewModel<LedgerUiState>(repository, LedgerUiState()) {

  private val searchFilter = MutableStateFlow("")
  private val typeFilter = MutableStateFlow("ALL")

  init {
    observeLedgerStreams()
  }

  private fun observeLedgerStreams() {
    observeFlow(
      flow = combine(repository.dailyLedger, searchFilter, typeFilter) { rows, query, tFilter ->
        val active = rows.filter { !it.isDeleted }.sortedByDescending { it.dateEpoch }
        val balances = linkedMapOf<String, Double>()
        active.sortedBy { it.dateEpoch }.forEach { entry ->
          when (entry.type) {
            "IN" -> balances[entry.mode] = (balances[entry.mode] ?: 0.0) + entry.amount
            "OUT" -> balances[entry.mode] = (balances[entry.mode] ?: 0.0) - entry.amount
            "TRANSFER" -> {
              balances[entry.mode] = (balances[entry.mode] ?: 0.0) - entry.amount
              if (entry.toMode.isNotBlank()) {
                balances[entry.toMode] = (balances[entry.toMode] ?: 0.0) + entry.amount
              }
            }
          }
        }
        val cashGroup = balances.entries
          .filter { it.key.contains("cash", ignoreCase = true) && !it.key.contains("jazz", ignoreCase = true) }
          .sumOf { it.value }
        val bankGroup = balances.entries
          .filter { it.key.contains("bank", ignoreCase = true) || it.key.contains("hbl", ignoreCase = true) || it.key.contains("meezan", ignoreCase = true) }
          .sumOf { it.value }
        val totalCashInHand = balances.values.sum()
        val othersGroup = totalCashInHand - cashGroup - bankGroup

        val inRows = active.filter { it.type == "IN" }
        val outRows = active.filter { it.type == "OUT" }
        val totalIn = inRows.sumOf { it.amount }
        val totalOut = outRows.sumOf { it.amount }

        val catSummary = outRows
          .groupBy { it.category.ifBlank { "General" } }
          .mapValues { (_, list) -> list.sumOf { it.amount } }
          .toList()
          .sortedByDescending { it.second }

        val channels = (listOf("Cash", "Bank Transfer", "Easypaisa", "JazzCash") +
          active.flatMap { listOf(it.mode, it.toMode) })
          .map { it.trim() }
          .filter { it.isNotBlank() }
          .distinct()

        val q = query.trim().lowercase()
        val filtered = active.filter { e ->
          val typeOk = tFilter == "ALL" || e.type == tFilter
          val queryOk = q.isEmpty() ||
            e.party.lowercase().contains(q) ||
            e.category.lowercase().contains(q) ||
            e.mode.lowercase().contains(q) ||
            e.toMode.lowercase().contains(q) ||
            e.note.lowercase().contains(q) ||
            e.amount.toString().contains(q)
          typeOk && queryOk
        }

        LedgerUiState(
          entries = active,
          filteredEntries = filtered,
          channelBalances = balances,
          knownChannels = channels,
          cashGroup = cashGroup,
          bankGroup = bankGroup,
          othersGroup = othersGroup,
          totalCashInHand = totalCashInHand,
          totalIn = totalIn,
          totalOut = totalOut,
          netBalance = totalIn - totalOut,
          inCount = inRows.size,
          outCount = outRows.size,
          categorySummary = catSummary,
          searchQuery = query,
          typeFilter = tFilter,
          isLoading = false,
          errorMessage = null,
        )
      }.flowOn(Dispatchers.Default),
      onError = { err ->
        updateState { it.copy(isLoading = false, errorMessage = err.localizedMessage ?: "Failed to load ledger") }
      },
    ) { computed ->
      updateState { current ->
        computed.copy(statusMessage = current.statusMessage)
      }
    }
  }

  fun setSearchQuery(query: String) {
    searchFilter.value = query
  }

  fun setTypeFilter(type: String) {
    typeFilter.value = type
  }

  fun saveEntry(
    editingId: String?,
    dateEpoch: Long,
    type: String,
    amount: Double,
    mode: String,
    toMode: String,
    party: String,
    refNo: String,
    note: String,
    category: String? = null,
    scope: String = "",
    onSaved: () -> Unit = {},
  ) {
    if (SarimFormat.isFutureDate(dateEpoch)) {
      updateState { it.copy(errorMessage = "Future dates are refused on ledger entries.") }
      return
    }
    launchRepoMutation(onError = { msg -> updateState { it.copy(errorMessage = msg) } }) {
      repository.saveDailyLedgerEntry(
        editingId = editingId,
        dateEpoch = dateEpoch,
        type = type,
        amount = amount,
        mode = mode,
        toMode = toMode,
        party = party,
        refNo = refNo,
        note = note,
        category = category,
        scope = scope,
      )
      updateState { it.copy(statusMessage = "Ledger entry saved") }
      onSaved()
    }
  }

  fun deleteEntry(id: String) {
    launchRepoMutation(onError = { msg -> updateState { it.copy(errorMessage = msg) } }) {
      repository.deleteDailyLedgerEntry(id)
      updateState { it.copy(statusMessage = "Moved to Recycle Bin") }
    }
  }

  fun clearMessages() {
    updateState { it.copy(statusMessage = null, errorMessage = null) }
  }

  class Factory(private val repository: SarimRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return BaseLedgerViewModel(repository) as T
    }
  }
}
