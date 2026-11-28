package com.example.util

enum class DialogTone(val label: String) {
  CONFIRM("Confirm"),
  PERMANENT("Permanent"),
  WARNING("Warning"),
  ERROR("Error"),
  SUCCESS("Success"),
}

data class DialogHighlight(
  val label: String,
  val value: String,
)

data class DialogChoice(
  val id: String,
  val label: String,
  val subtitle: String? = null,
)

/** Optional tick box inside a dialog (web `dialogBoxes`: "Share invoice after saving"). */
data class DialogCheckbox(
  val label: String,
  val checked: Boolean,
  /** Replaces the confirm button text while ticked, e.g. "Save & Share". */
  val confirmLabelWhenChecked: String? = null,
  /** Called with the final tick state when the person confirms. */
  val onChanged: (Boolean) -> Unit = {},
)

data class AppDialogRequest(
  val title: String,
  val body: String,
  val tone: DialogTone? = null,
  val highlight: DialogHighlight? = null,
  val choices: List<DialogChoice> = emptyList(),
  val confirmLabel: String = "Confirm",
  val cancelLabel: String? = "Cancel",
  val requirePassword: Boolean = false,
  val passwordHint: String = "Account password",
  val onConfirm: (String) -> Unit = {},
  val onChoiceSelected: (String) -> Unit = {},
  val onCancel: () -> Unit = {},
  val checkbox: DialogCheckbox? = null,
)

/**
 * Web `pickTone()` equivalent for dialogs that don't pass an explicit tone: destructive wording is
 * permanent, titles matching the web warning pattern (or a body starting with "Warning"/"Caution") are
 * warnings, and everything else is a plain confirmation.
 */
fun inferDialogTone(title: String, body: String): DialogTone {
  val text = "$title $body".lowercase()
  return when {
    listOf("delete", "erase", "empty bin", "permanent", "remove").any { it in text } -> DialogTone.PERMANENT
    DialogMessageParser.warnRe.containsMatchIn(title) ||
      Regex("^\\s*(warning|caution)", RegexOption.IGNORE_CASE).containsMatchIn(body) -> DialogTone.WARNING
    listOf("error", "failed", "invalid", "required", "refused", "duplicate").any { it in text } -> DialogTone.ERROR
    listOf("saved", "success", "restored", "synced", "complete").any { it in text } -> DialogTone.SUCCESS
    else -> DialogTone.CONFIRM
  }
}
