package com.example.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.SarimPalette
import com.example.ui.theme.TabularNumberStyle
import com.example.util.AppDialogRequest
import com.example.util.CalloutKind
import com.example.util.DialogBlock
import com.example.util.DialogFact
import com.example.util.DialogMessageParser
import com.example.util.DialogTone
import com.example.util.inferDialogTone

/** Web `.dlg-overlay[data-tone]` gradient pair (--g1 → --g2) for each tone, per theme. */
private data class DlgTone(val g1: Color, val g2: Color)

private val DlgInk = Color(0xFF03170B)
private val DlgLime = Color(0xFFA3E635)

private fun toneStyle(tone: DialogTone, dark: Boolean): DlgTone {
  val cyan = if (dark) Color(0xFF67E8F9) else Color(0xFF0891B2)
  val accent = if (dark) Color(0xFF4ADE80) else Color(0xFF10B981)
  val warning = if (dark) Color(0xFFFBBF24) else Color(0xFFD97706)
  val danger = if (dark) Color(0xFFF87171) else Color(0xFFDC2626)
  return when (tone) {
    DialogTone.CONFIRM -> DlgTone(cyan, DlgLime)
    DialogTone.SUCCESS -> DlgTone(accent, DlgLime)
    DialogTone.WARNING -> DlgTone(warning, if (dark) Color(0xFFFBBF24) else Color(0xFFD97706))
    DialogTone.PERMANENT -> DlgTone(danger, warning)
    DialogTone.ERROR -> DlgTone(danger, danger)
  }
}

/**
 * Card outline of the web `.dlg-card`: a 26dp rounded rectangle with a rounded notch cut out of the
 * top-right corner, where the tone chip sits.
 */
private class NotchedCardShape(
  private val cornerRadius: Dp,
  private val notchWidth: Dp,
  private val notchHeight: Dp,
  private val notchRadius: Dp,
) : Shape {
  override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
    val r = with(density) { cornerRadius.toPx() }
    val nw = with(density) { notchWidth.toPx() }.coerceAtMost(size.width - 2 * r)
    val nh = with(density) { notchHeight.toPx() }
    val nr = with(density) { notchRadius.toPx() }.coerceAtMost(minOf(nw, nh))
    val w = size.width
    val h = size.height
    val x0 = w - nw
    val path = Path().apply {
      moveTo(r, 0f)
      lineTo(x0, 0f)
      lineTo(x0, nh - nr)
      arcTo(Rect(x0, nh - 2 * nr, x0 + 2 * nr, nh), 180f, -90f, false)
      lineTo(w, nh)
      lineTo(w, h - r)
      arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
      lineTo(r, h)
      arcTo(Rect(0f, h - 2 * r, 2 * r, h), 90f, 90f, false)
      lineTo(0f, r)
      arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
      close()
    }
    return Outline.Generic(path)
  }
}

private fun toneIcon(tone: DialogTone, title: String, confirmLabel: String): ImageVector = when (tone) {
  DialogTone.PERMANENT ->
    if (DialogMessageParser.deleteRe.containsMatchIn("$title $confirmLabel")) Icons.Rounded.Delete else Icons.Rounded.Warning
  DialogTone.WARNING -> Icons.Rounded.Warning
  DialogTone.ERROR -> Icons.Rounded.ErrorOutline
  DialogTone.SUCCESS -> Icons.Rounded.CheckCircle
  DialogTone.CONFIRM -> Icons.AutoMirrored.Rounded.HelpOutline
}

/** Android counterpart of the web app's glass dialog (modules/dialog.js + `.dlg-*` styles). */
@Composable
fun GlassAlertDialog(
  request: AppDialogRequest,
  onDismiss: () -> Unit,
) {
  val palette = LocalSarimPalette.current
  val tone = request.tone ?: inferDialogTone(request.title, request.body)
  val style = toneStyle(tone, palette.isDark)
  val parsed = remember(request.body) { DialogMessageParser.parse(request.body) }
  val figure = request.highlight?.let { DialogFact(it.label, it.value) } ?: parsed.figure
  val glyph = if (request.requirePassword) Icons.Rounded.Lock else toneIcon(tone, request.title, request.confirmLabel)

  var passwordText by remember { mutableStateOf("") }
  var showPassword by remember { mutableStateOf(false) }
  var passwordError by remember { mutableStateOf<String?>(null) }
  var boxChecked by remember { mutableStateOf(request.checkbox?.checked ?: false) }
  var chipSize by remember { mutableStateOf(Size(130f, 46f)) }
  val density = LocalDensity.current

  val enter = remember { Animatable(0f) }
  LaunchedEffect(Unit) { enter.animateTo(1f, tween(180)) }

  val cancel = {
    request.onCancel()
    onDismiss()
  }
  val safeFirst = tone == DialogTone.PERMANENT || request.requirePassword

  Dialog(
    onDismissRequest = cancel,
    properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = !safeFirst),
  ) {
    // Web `--scrim` (66% dark / 42% light) with a soft blur behind the dialog where supported.
    val view = LocalView.current
    SideEffect {
      val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
      window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
      window.setDimAmount(if (palette.isDark) 0.66f else 0.42f)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        val attrs = window.attributes
        attrs.blurBehindRadius = 14
        window.attributes = attrs
      }
    }

    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp
    val chipWidthDp = with(density) { chipSize.width.toDp() }
    val chipHeightDp = with(density) { chipSize.height.toDp() }
    val shape = NotchedCardShape(28.dp, chipWidthDp + 10.dp, chipHeightDp + 10.dp, 20.dp)
    val line = palette.textPrimary.copy(alpha = 0.12f)
    val faint = palette.textMuted.copy(alpha = 0.85f)

    Box(
      modifier = Modifier
        .widthIn(max = 400.dp)
        .fillMaxWidth(0.94f)
        .heightIn(max = maxHeight)
        .graphicsLayer {
          alpha = enter.value
          val s = 0.96f + 0.04f * enter.value
          scaleX = s
          scaleY = s
        },
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .shadow(24.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.4f))
          .clip(shape)
          .background(Brush.verticalGradient(listOf(palette.surfaceElevated, palette.surface)))
          .drawBehind {
            drawRect(
              brush = Brush.radialGradient(
                colors = listOf(style.g1.copy(alpha = if (palette.isDark) 0.16f else 0.10f), Color.Transparent),
                center = Offset.Zero,
                radius = size.width * 0.9f,
              ),
            )
          }
          .border(1.dp, line, shape),
      ) {
        // Top: gradient glyph (+ caption for the password dialog), kept clear of the chip.
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, top = 20.dp, end = chipWidthDp + 14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          Box(
            modifier = Modifier
              .size(46.dp)
              .clip(RoundedCornerShape(13.dp))
              .background(Brush.linearGradient(listOf(style.g1, style.g2))),
            contentAlignment = Alignment.Center,
          ) {
            Icon(glyph, contentDescription = null, tint = DlgInk, modifier = Modifier.size(26.dp))
          }
          if (request.requirePassword) {
            Text("Account password required", color = palette.textMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
          }
        }

        Text(
          text = request.title.trim(),
          color = palette.textPrimary,
          fontSize = 21.sp,
          lineHeight = 26.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp),
        )

        val hasBody = parsed.blocks.isNotEmpty() || request.choices.isNotEmpty() || request.requirePassword || request.checkbox != null
        if (hasBody) {
          Column(
            modifier = Modifier
              .weight(1f, fill = false)
              .verticalScroll(rememberScrollState())
              .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
          ) {
            parsed.blocks.forEach { block -> DialogBlockView(block, style, palette, faint, line) }

            if (request.choices.isNotEmpty()) {
              Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val scheme = androidx.compose.material3.MaterialTheme.colorScheme
                request.choices.forEach { choice ->
                  Surface(
                    modifier = Modifier
                      .fillMaxWidth()
                      .defaultMinSize(minHeight = 52.dp)
                      .clip(RoundedCornerShape(20.dp))
                      .clickable {
                        request.onChoiceSelected(choice.id)
                        onDismiss()
                      },
                    shape = RoundedCornerShape(20.dp),
                    color = scheme.surfaceContainerHigh,
                  ) {
                    Row(
                      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                      Column(modifier = Modifier.weight(1f)) {
                        Text(choice.label, color = palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        if (!choice.subtitle.isNullOrBlank()) {
                          Text(choice.subtitle, color = faint, fontSize = 12.sp)
                        }
                      }
                      Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = faint, modifier = Modifier.size(18.dp))
                    }
                  }
                }
              }
            }

            request.checkbox?.let { cb ->
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(14.dp))
                  .clickable { boxChecked = !boxChecked },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                androidx.compose.material3.Checkbox(
                  checked = boxChecked,
                  onCheckedChange = { boxChecked = it },
                  colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = style.g1),
                )
                Text(cb.label, color = palette.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
              }
            }

            if (request.requirePassword) {
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SarimTextField(
                  value = passwordText,
                  onValueChange = {
                    passwordText = it
                    passwordError = null
                  },
                  label = "",
                  placeholder = request.passwordHint,
                  isPassword = true,
                  passwordVisible = showPassword,
                  trailingContent = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                      Icon(
                        if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (showPassword) "Hide password" else "Show password",
                        tint = if (showPassword) style.g1 else faint,
                      )
                    }
                  },
                  testTag = "dialog_password_input",
                )
                passwordError?.let { Text(it, color = palette.red, fontSize = 13.sp) }
              }
            }
          }
        } else {
          Box(Modifier.height(18.dp))
        }

        // Footer: optional figure, then the action buttons (ghost : solid = 1 : 3 like the web).
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .background(palette.textPrimary.copy(alpha = 0.055f))
            .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 20.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          if (figure != null) {
            Column {
              Text(figure.key, color = faint, fontSize = 12.sp)
              Text(
                figure.value,
                color = palette.textPrimary,
                style = TabularNumberStyle.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
          Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (request.cancelLabel != null) {
              DlgButton(
                text = request.cancelLabel,
                solid = false,
                style = style,
                modifier = Modifier.weight(1f),
                testTag = "dialog_cancel_btn",
                onClick = cancel,
              )
            }
            if (request.choices.isEmpty()) {
              DlgButton(
                text = request.checkbox?.takeIf { boxChecked }?.confirmLabelWhenChecked ?: request.confirmLabel,
                solid = true,
                style = style,
                modifier = Modifier.weight(if (request.cancelLabel != null) 3f else 1f),
                testTag = "dialog_confirm_btn",
                onClick = {
                  if (request.requirePassword && passwordText.isEmpty()) {
                    passwordError = "Please enter your password."
                  } else {
                    request.checkbox?.onChanged?.invoke(boxChecked)
                    request.onConfirm(passwordText)
                    onDismiss()
                  }
                },
              )
            }
          }
        }
      }

      // Tone chip, sitting in the notch of the card's top-right corner.
      Row(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .onSizeChanged { chipSize = Size(it.width.toFloat(), it.height.toFloat()) }
          .height(36.dp)
          .clip(CircleShape)
          .background(lerp(palette.background, palette.textPrimary, 0.14f))
          .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(glyph, contentDescription = null, tint = style.g2, modifier = Modifier.size(16.dp))
        Text(
          text = tone.label.uppercase(),
          color = palette.textPrimary,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 0.96.sp,
          maxLines = 1,
        )
      }
    }
  }
}

@Composable
private fun DialogBlockView(block: DialogBlock, style: DlgTone, palette: SarimPalette, faint: Color, line: Color) {
  when (block) {
    is DialogBlock.Paragraph -> Text(
      block.text,
      color = if (block.lead) palette.textPrimary else palette.textMuted,
      fontSize = 14.sp,
      lineHeight = 22.sp,
    )
    is DialogBlock.Subhead -> Text(block.text, color = palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    is DialogBlock.Points -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      block.items.forEach { item ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("•", color = style.g1, fontSize = 14.sp, fontWeight = FontWeight.Bold)
          Text(item, color = palette.textMuted, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.weight(1f))
        }
      }
    }
    is DialogBlock.Facts ->
      if (block.items.size <= 3) {
        // Web `.dlg-meta`: horizontal columns separated by hairlines.
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
          block.items.forEachIndexed { i, f ->
            if (i > 0) Box(Modifier.fillMaxHeight().width(1.dp).background(line))
            Column(
              modifier = Modifier
                .weight(1f)
                .padding(start = if (i == 0) 0.dp else 14.dp, end = if (i == block.items.lastIndex) 0.dp else 14.dp),
            ) {
              Text(f.key, color = faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text(f.value, color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
          }
        }
      } else {
        // Web `.dlg-facts`: label left, tabular value right, hairline rows.
        Column(modifier = Modifier.fillMaxWidth()) {
          Box(Modifier.fillMaxWidth().height(1.dp).background(line))
          block.items.forEach { f ->
            Row(
              modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
              horizontalArrangement = Arrangement.spacedBy(16.dp),
              verticalAlignment = Alignment.Bottom,
            ) {
              Text(f.key, color = faint, fontSize = 13.sp, modifier = Modifier.weight(1f))
              Text(
                f.value,
                color = palette.textPrimary,
                style = TabularNumberStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.End,
              )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(line))
          }
        }
      }
    is DialogBlock.Callout -> {
      val tint = if (block.kind == CalloutKind.DANGER) palette.red else if (palette.isDark) Color(0xFFFBBF24) else Color(0xFFB45309)
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(18.dp))
          .background(tint.copy(alpha = 0.12f))
          .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(block.label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(block.text, color = palette.textPrimary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
      }
    }
  }
}

/** Web `.dlg-btn`: 48dp pill; ghost = secondary tonal pill, solid = tone gradient with ink text. */
@Composable
private fun DlgButton(
  text: String,
  solid: Boolean,
  style: DlgTone,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  testTag: String = "",
) {
  val scheme = androidx.compose.material3.MaterialTheme.colorScheme
  val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
  val (scale, corner) = rememberExpressivePress(source, restCorner = 24.dp, pressedCorner = 14.dp)
  val shape = RoundedCornerShape(corner)
  Box(
    modifier = modifier
      .height(48.dp)
      .graphicsLayer { scaleX = scale; scaleY = scale }
      .testTag(testTag)
      .then(
        if (solid) {
          Modifier
            .shadow(6.dp, shape, ambientColor = style.g1.copy(alpha = 0.5f), spotColor = style.g1.copy(alpha = 0.5f))
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(style.g1, style.g2)))
        } else {
          Modifier
            .clip(shape)
            .background(scheme.secondaryContainer)
        },
      )
      .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text,
      color = if (solid) DlgInk else scheme.onSecondaryContainer,
      fontSize = 15.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(horizontal = 14.dp),
    )
  }
}
