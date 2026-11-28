package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.repository.FailedOp
import com.example.data.repository.FailedOpsQueue
import com.example.ui.theme.LocalSarimPalette
import com.example.viewmodel.SarimViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Web "Failed Uploads" modal: Retry / Dismiss per operation, Export JSON and Dismiss all. */
@Composable
fun FailedOpsDialog(ops: List<FailedOp>, viewModel: SarimViewModel) {
  val palette = LocalSarimPalette.current
  val context = LocalContext.current
  val stamp = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())

  Dialog(onDismissRequest = { viewModel.closeFailedOps() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(
      modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 520.dp).testTag("failed_ops_dialog"),
      shape = RoundedCornerShape(28.dp),
      color = palette.surface,
      border = BorderStroke(1.dp, palette.red.copy(alpha = 0.45f)),
    ) {
      Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Failed Uploads", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(
          "These operations exhausted all ${FailedOpsQueue.MAX_RETRIES} retry attempts. Retry to re-attempt upload, or dismiss to discard.",
          color = palette.textMuted,
          fontSize = 12.sp,
        )
        LazyColumn(modifier = Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          items(ops, key = { it.id }) { op ->
            Surface(
              shape = RoundedCornerShape(16.dp),
              color = palette.surfaceElevated,
              border = BorderStroke(1.dp, palette.border),
            ) {
              Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(op.label, color = palette.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                  if (op.failedAt > 0L) stamp.format(Date(op.failedAt)) else "—",
                  color = palette.textMuted,
                  fontSize = 11.sp,
                )
                if (op.finalError.isNotBlank()) {
                  Text(op.finalError, color = palette.red, fontSize = 11.5.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  SarimPrimaryButton("Retry", onClick = { viewModel.retryFailedOp(op.id) }, modifier = Modifier.weight(1f), testTag = "failed_op_retry")
                  SarimSecondaryButton("Dismiss", onClick = { viewModel.dismissFailedOp(op.id) }, modifier = Modifier.weight(1f), testTag = "failed_op_dismiss")
                }
              }
            }
          }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          SarimSecondaryButton("Export JSON", onClick = { viewModel.exportFailedOps(context) }, modifier = Modifier.weight(1f), testTag = "failed_ops_export")
          SarimDangerButton("Dismiss all", onClick = { viewModel.dismissAllFailedOps() }, modifier = Modifier.weight(1f), testTag = "failed_ops_dismiss_all")
        }
      }
    }
  }
}
