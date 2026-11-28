package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.LocalSarimPalette
import com.example.util.AppDialogRequest
import com.example.util.DialogChoice
import com.example.util.DialogTone
import com.example.util.SarimFormat
import com.example.util.StatementFormat
import com.example.util.StatementSender
import com.example.util.StatementSpec
import com.example.viewmodel.SarimViewModel

/** Web `promptStatementRange`: pick the From / To dates a statement should cover. */
@Composable
fun StatementRangeDialog(
  title: String,
  minEpoch: Long,
  maxEpoch: Long,
  onDismiss: () -> Unit,
  onConfirm: (from: Long, to: Long) -> Unit,
) {
  val palette = LocalSarimPalette.current
  val now = System.currentTimeMillis()
  var from by remember { mutableLongStateOf(if (minEpoch > 0L) minEpoch else now) }
  var to by remember { mutableLongStateOf(if (maxEpoch > 0L) minOf(maxEpoch, now) else now) }
  var error by remember { mutableStateOf<String?>(null) }

  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    Surface(
      modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 460.dp),
      shape = RoundedCornerShape(32.dp),
      color = scheme.surfaceContainerLow,
    ) {
      Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("Pick the dates to include", color = palette.textMuted, fontSize = 12.5.sp)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
          SarimDatePickerField(label = "From", dateEpoch = from, onDateSelected = { from = it; error = null }, modifier = Modifier.weight(1f), testTag = "stmt_from")
          SarimDatePickerField(label = "To", dateEpoch = to, onDateSelected = { to = it; error = null }, modifier = Modifier.weight(1f), testTag = "stmt_to")
        }
        error?.let { Text(it, color = palette.red, fontSize = 13.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          SarimSecondaryButton("Cancel", onClick = onDismiss, modifier = Modifier.weight(1f))
          SarimPrimaryButton(
            text = "Continue",
            onClick = {
              when {
                SarimFormat.dateInput(from) > SarimFormat.dateInput(to) -> error = "The From date must be on or before the To date."
                SarimFormat.isFutureDate(to) -> error = "Future dates are not allowed."
                else -> onConfirm(from, to)
              }
            },
            modifier = Modifier.weight(1f),
            testTag = "stmt_range_continue",
          )
        }
      }
    }
  }
}

/**
 * The web "Share Statement" flow for a customer, rep customer or payment entity:
 * date range, then PDF / PDF to WhatsApp / image to WhatsApp / WhatsApp text / SMS.
 */
@Composable
fun StatementShareFlow(
  viewModel: SarimViewModel,
  personName: String,
  minEpoch: Long,
  maxEpoch: Long,
  buildSpec: (from: Long, to: Long) -> StatementSpec,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  StatementRangeDialog(
    title = "Statement Range",
    minEpoch = minEpoch,
    maxEpoch = maxEpoch,
    onDismiss = onDismiss,
    onConfirm = { from, to ->
      onDismiss()
      val spec = buildSpec(from, to)
      viewModel.showDialog(
        AppDialogRequest(
          title = "Share Statement",
          body = "Choose how you want to share $personName's statement.",
          tone = DialogTone.CONFIRM,
          choices = listOf(
            DialogChoice(StatementFormat.PDF, "PDF", "Share as a PDF file"),
            DialogChoice(StatementFormat.PDF_WHATSAPP, "PDF to WhatsApp", "Send the PDF straight to their chat"),
            DialogChoice(StatementFormat.IMAGE_WHATSAPP, "Image to WhatsApp", "Send the statement as pictures"),
            DialogChoice(StatementFormat.WHATSAPP_TEXT, "WhatsApp message", "Send the statement as text"),
            DialogChoice(StatementFormat.SMS, "SMS", "Open Messages with the statement text"),
          ),
          onChoiceSelected = { fmt ->
            StatementSender.send(context, spec, fmt)?.let { err ->
              // the choice dialog dismisses itself right after this callback, so show the error on the next frame
              android.os.Handler(android.os.Looper.getMainLooper()).post {
                viewModel.showDialog(AppDialogRequest("Share Failed", err, tone = DialogTone.ERROR, cancelLabel = null))
              }
            }
          },
        )
      )
    },
  )
}
