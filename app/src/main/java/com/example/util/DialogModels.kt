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

data class AppDialogRequest(
  val title: String,
  val body: String,
  val tone: DialogTone? = null,
  val highlight: DialogHighlight? = null,
  val confirmLabel: String = "Confirm",
  val cancelLabel: String? = "Cancel",
  val requirePassword: Boolean = false,
  val passwordHint: String = "Account password",
  val onConfirm: (String) -> Unit = {},
  val onCancel: () -> Unit = {},
)

fun inferDialogTone(title: String, body: String): DialogTone {
  val text = "$title $body".lowercase()
  return when {
    listOf("delete", "erase", "empty bin", "permanent", "remove").any { it in text } -> DialogTone.PERMANENT
    listOf("insufficient", "exceed", "short", "over", "warning", "over-collection", "future").any { it in text } -> DialogTone.WARNING
    listOf("error", "failed", "invalid", "required", "refused", "duplicate").any { it in text } -> DialogTone.ERROR
    listOf("saved", "success", "restored", "synced", "complete").any { it in text } -> DialogTone.SUCCESS
    else -> DialogTone.CONFIRM
  }
}
