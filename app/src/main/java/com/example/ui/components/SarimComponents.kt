package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.util.SarimFormat

@Composable
fun ShimmerHeading(
  text: String,
  modifier: Modifier = Modifier,
  fontSizeSp: Int = 26,
  tint: Color? = null,
) {
  val palette = LocalSarimPalette.current
  val baseColor = tint ?: palette.green
  val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
  val shift by infiniteTransition.animateFloat(
    initialValue = -200f,
    targetValue = 600f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 2800, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "shimmer_shift",
  )
  val brush = Brush.linearGradient(
    colors = listOf(baseColor, palette.textPrimary, palette.cyan, baseColor),
    start = Offset(shift, 0f),
    end = Offset(shift + 220f, 40f),
  )
  Text(
    text = text,
    modifier = modifier,
    style = TextStyle(
      fontFamily = PlayfairDisplayFontFamily,
      fontWeight = FontWeight.Bold,
      fontSize = fontSizeSp.sp,
      brush = brush,
    ),
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

@Composable
fun SarimCard(
  modifier: Modifier = Modifier,
  borderColor: Color? = null,
  containerColor: Color? = null,
  contentPadding: Dp = 16.dp,
  content: @Composable ColumnScope.() -> Unit,
) {
  val palette = LocalSarimPalette.current
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    color = containerColor ?: palette.surface,
    border = BorderStroke(1.dp, borderColor ?: palette.border),
    tonalElevation = 1.dp,
  ) {
    Column(
      modifier = Modifier.padding(contentPadding),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      content = content,
    )
  }
}

@Composable
fun <T> SarimToggleGroup(
  options: List<T>,
  selected: T,
  labelOf: (T) -> String,
  onSelect: (T) -> Unit,
  modifier: Modifier = Modifier,
  colorOf: ((T) -> Color)? = null,
  testTagPrefix: String = "toggle",
) {
  val palette = LocalSarimPalette.current
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    options.forEach { opt ->
      val isSelected = opt == selected
      val accent = colorOf?.invoke(opt) ?: palette.green
      Surface(
        modifier = Modifier
          .weight(1f)
          .defaultMinSize(minHeight = 44.dp)
          .clip(RoundedCornerShape(50))
          .testTag("${testTagPrefix}_${labelOf(opt).lowercase().replace(" ", "_")}")
          .clickable { onSelect(opt) },
        shape = RoundedCornerShape(50),
        color = if (isSelected) accent.copy(alpha = 0.16f) else palette.surfaceElevated,
        border = BorderStroke(1.dp, if (isSelected) accent else palette.border),
      ) {
        Box(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = labelOf(opt),
            color = if (isSelected) accent else palette.textMuted,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
          )
        }
      }
    }
  }
}

@Composable
fun SarimFieldLabel(text: String, modifier: Modifier = Modifier) {
  val palette = LocalSarimPalette.current
  Text(
    text = text.uppercase(),
    color = palette.textMuted,
    fontSize = 10.5.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 0.9.sp,
    modifier = modifier,
  )
}

@Composable
fun SarimTextField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  modifier: Modifier = Modifier,
  placeholder: String = "",
  prefixText: String? = null,
  suffixText: String? = null,
  readOnly: Boolean = false,
  dashedReadOnly: Boolean = false,
  keyboardType: KeyboardType = KeyboardType.Text,
  isPassword: Boolean = false,
  passwordVisible: Boolean = false,
  leadingIcon: ImageVector? = null,
  trailingContent: (@Composable () -> Unit)? = null,
  maxLength: Int = 200,
  testTag: String = "field",
  textFieldModifier: Modifier = Modifier,
  onClickIfReadOnly: (() -> Unit)? = null,
) {
  val palette = LocalSarimPalette.current
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (label.isNotBlank()) SarimFieldLabel(label)
    val shape = RoundedCornerShape(12.dp)
    val borderColor = if (dashedReadOnly) palette.cyan.copy(alpha = 0.45f) else palette.border
    OutlinedTextField(
      value = value,
      onValueChange = { if (it.length <= maxLength) onValueChange(it) },
      modifier = textFieldModifier
        .fillMaxWidth()
        .testTag(testTag)
        .let { m -> if (readOnly && onClickIfReadOnly != null) m.clickable { onClickIfReadOnly() } else m },
      enabled = !(readOnly && onClickIfReadOnly != null),
      readOnly = readOnly,
      singleLine = true,
      placeholder = if (placeholder.isNotBlank()) {
        { Text(placeholder, color = palette.textMuted.copy(alpha = 0.65f), fontSize = 14.sp) }
      } else null,
      prefix = if (prefixText != null) {
        { Text("$prefixText ", color = palette.textMuted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
      } else null,
      suffix = if (suffixText != null) {
        { Text(" $suffixText", color = palette.textMuted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
      } else null,
      leadingIcon = leadingIcon?.let { icon ->
        { Icon(icon, contentDescription = null, tint = palette.textMuted, modifier = Modifier.size(18.dp)) }
      },
      trailingIcon = trailingContent,
      visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
      keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
      keyboardActions = KeyboardActions.Default,
      textStyle = TextStyle(
        fontFamily = if (keyboardType == KeyboardType.Decimal || keyboardType == KeyboardType.Number || keyboardType == KeyboardType.Phone) ManropeFontFamily else PlusJakartaFontFamily,
        fontFeatureSettings = "tnum",
        fontSize = 14.5.sp,
        color = palette.textPrimary,
      ),
      shape = shape,
      colors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = palette.surfaceElevated,
        unfocusedContainerColor = palette.surfaceElevated,
        disabledContainerColor = palette.surfaceElevated,
        focusedBorderColor = palette.green,
        unfocusedBorderColor = borderColor,
        disabledBorderColor = borderColor,
        disabledTextColor = palette.textPrimary,
        cursorColor = palette.green,
      ),
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SarimSearchFieldWithDropdown(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  placeholder: String,
  suggestions: List<String>,
  onSuggestionSelected: (String) -> Unit,
  modifier: Modifier = Modifier,
  leadingIcon: ImageVector? = null,
  testTag: String = "search_dropdown",
) {
  val palette = LocalSarimPalette.current
  val focusManager = LocalFocusManager.current
  var showDropdown by remember { mutableStateOf(false) }
  var filterActive by remember { mutableStateOf(false) }

  val displayedSuggestions = remember(value, suggestions, filterActive) {
    val distinct = suggestions.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (!filterActive || value.isBlank()) {
      distinct.take(8)
    } else {
      val q = value.trim()
      val matches = distinct.filter { it.contains(q, ignoreCase = true) }
      (if (matches.isNotEmpty()) matches else distinct).take(8)
    }
  }
  val isMenuVisible = showDropdown && displayedSuggestions.isNotEmpty()

  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (label.isNotBlank()) SarimFieldLabel(label)
    ExposedDropdownMenuBox(
      expanded = isMenuVisible,
      onExpandedChange = { open ->
        filterActive = false
        showDropdown = open
      },
      modifier = Modifier.fillMaxWidth(),
    ) {
      SarimTextField(
        value = value,
        onValueChange = {
          onValueChange(it)
          filterActive = true
          showDropdown = true
        },
        label = "",
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingContent = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            if (value.isNotEmpty()) {
              IconButton(
                onClick = {
                  onValueChange("")
                  filterActive = false
                  showDropdown = true
                },
                modifier = Modifier.size(40.dp).testTag("${testTag}_clear"),
              ) {
                Icon(
                  Icons.Filled.Close,
                  contentDescription = "Clear text",
                  tint = palette.textMuted,
                  modifier = Modifier.size(16.dp),
                )
              }
            }
            ExposedDropdownMenuDefaults.TrailingIcon(expanded = isMenuVisible)
          }
        },
        textFieldModifier = Modifier
          .menuAnchor(MenuAnchorType.PrimaryEditable, enabled = true)
          .onFocusChanged { focusState ->
            if (!focusState.isFocused && !showDropdown) {
              filterActive = false
            }
          },
        testTag = testTag,
      )

      ExposedDropdownMenu(
        expanded = isMenuVisible,
        onDismissRequest = {
          showDropdown = false
          filterActive = false
        },
        modifier = Modifier
          .exposedDropdownSize(matchTextFieldWidth = true)
          .heightIn(max = 280.dp)
          .background(palette.surfaceElevated)
          .border(1.dp, palette.green.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
      ) {
        displayedSuggestions.forEachIndexed { idx, item ->
          val isCurrent = item.equals(value.trim(), ignoreCase = true)
          DropdownMenuItem(
            modifier = Modifier
              .fillMaxWidth()
              .defaultMinSize(minHeight = 48.dp)
              .testTag("${testTag}_option_$idx"),
            text = {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Text(
                  text = item,
                  color = if (isCurrent) palette.green else palette.textPrimary,
                  fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                  fontSize = 13.5.sp,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f),
                )
                if (isCurrent) {
                  Icon(Icons.Filled.Check, contentDescription = null, tint = palette.green, modifier = Modifier.size(16.dp))
                }
              }
            },
            onClick = {
              onSuggestionSelected(item)
              filterActive = false
              showDropdown = false
              focusManager.clearFocus()
            },
          )
          if (idx < displayedSuggestions.lastIndex) {
            HorizontalDivider(color = palette.border)
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SarimPopoverSelector(
  label: String,
  selectedText: String,
  options: List<String>,
  onSelect: (Int, String) -> Unit,
  modifier: Modifier = Modifier,
  testTag: String = "popover_selector",
) {
  val palette = LocalSarimPalette.current
  var expanded by remember { mutableStateOf(false) }
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (label.isNotBlank()) SarimFieldLabel(label)
    ExposedDropdownMenuBox(
      expanded = expanded,
      onExpandedChange = { expanded = it },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
          .defaultMinSize(minHeight = 48.dp)
          .clip(RoundedCornerShape(12.dp))
          .testTag(testTag)
          .clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        color = palette.surfaceElevated,
        border = BorderStroke(1.dp, if (expanded) palette.green else palette.border),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = selectedText,
            color = palette.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
        }
      }
      ExposedDropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        modifier = Modifier
          .exposedDropdownSize(matchTextFieldWidth = true)
          .heightIn(max = 320.dp)
          .background(palette.surfaceElevated)
          .border(1.dp, palette.green.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
      ) {
        options.forEachIndexed { idx, opt ->
          val isSelected = opt == selectedText
          DropdownMenuItem(
            modifier = Modifier
              .fillMaxWidth()
              .defaultMinSize(minHeight = 48.dp)
              .testTag("${testTag}_item_$idx"),
            text = {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Text(
                  opt,
                  color = if (isSelected) palette.green else palette.textPrimary,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                  modifier = Modifier.weight(1f),
                )
                if (isSelected) {
                  Icon(Icons.Filled.Check, contentDescription = null, tint = palette.green, modifier = Modifier.size(16.dp))
                }
              }
            },
            onClick = {
              onSelect(idx, opt)
              expanded = false
            },
          )
          if (idx < options.lastIndex) {
            HorizontalDivider(color = palette.border)
          }
        }
      }
    }
  }
}

@Composable
fun SarimPrimaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  testTag: String = "primary_button",
) {
  val palette = LocalSarimPalette.current
  val gradient = Brush.horizontalGradient(listOf(palette.gradientStart, palette.gradientEnd))
  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(50.dp)
      .clip(RoundedCornerShape(50))
      .background(
        brush = if (enabled) gradient else Brush.horizontalGradient(listOf(palette.border, palette.border)),
      )
      .testTag(testTag)
      .clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      color = if (enabled) Color(0xFF022112) else palette.textMuted,
      fontWeight = FontWeight.Bold,
      fontSize = 14.5.sp,
    )
  }
}

@Composable
fun SarimSecondaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  testTag: String = "secondary_button",
) {
  val palette = LocalSarimPalette.current
  Surface(
    modifier = modifier
      .defaultMinSize(minHeight = 46.dp)
      .clip(RoundedCornerShape(50))
      .testTag(testTag)
      .clickable(onClick = onClick),
    shape = RoundedCornerShape(50),
    color = palette.surfaceElevated,
    border = BorderStroke(1.dp, palette.border),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (icon != null) {
        Icon(icon, contentDescription = null, tint = palette.textPrimary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
      }
      Text(text, color = palette.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
  }
}

@Composable
fun SarimDangerButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  icon: ImageVector? = null,
  testTag: String = "danger_button",
) {
  val palette = LocalSarimPalette.current
  Surface(
    modifier = modifier
      .defaultMinSize(minHeight = 46.dp)
      .clip(RoundedCornerShape(50))
      .testTag(testTag)
      .clickable(enabled = enabled, onClick = onClick),
    shape = RoundedCornerShape(50),
    color = if (enabled) palette.red.copy(alpha = 0.16f) else palette.surfaceElevated,
    border = BorderStroke(1.dp, if (enabled) palette.red else palette.border),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (icon != null) {
        Icon(icon, contentDescription = null, tint = if (enabled) palette.red else palette.textMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
      }
      Text(text, color = if (enabled) palette.red else palette.textMuted, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
    }
  }
}

@Composable
fun SarimStatTile(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
  accentColor: Color? = null,
  subText: String? = null,
) {
  val palette = LocalSarimPalette.current
  val edge = accentColor ?: palette.border
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(14.dp),
    color = palette.surfaceElevated,
    border = BorderStroke(1.dp, edge.copy(alpha = if (accentColor != null) 0.5f else 1f)),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = label.uppercase(),
        color = palette.textMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.7.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = value,
        color = accentColor ?: palette.textPrimary,
        style = TabularNumberStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (!subText.isNullOrBlank()) {
        Text(
          text = subText,
          color = palette.textMuted,
          fontSize = 10.5.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
fun SarimChip(
  text: String,
  color: Color,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(50),
    color = color.copy(alpha = 0.16f),
    border = BorderStroke(1.dp, color.copy(alpha = 0.55f)),
  ) {
    Text(
      text = text,
      color = color,
      fontSize = 10.5.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
      maxLines = 1,
    )
  }
}

@Composable
fun SarimCallout(
  text: String,
  toneColor: Color,
  modifier: Modifier = Modifier,
  icon: ImageVector? = Icons.Filled.Warning,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    color = toneColor.copy(alpha = 0.12f),
    border = BorderStroke(1.dp, toneColor.copy(alpha = 0.45f)),
  ) {
    Row(
      modifier = Modifier.padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (icon != null) {
        Icon(icon, contentDescription = null, tint = toneColor, modifier = Modifier.size(18.dp))
      }
      Text(text, color = toneColor, fontSize = 12.5.sp, lineHeight = 17.sp)
    }
  }
}

@Composable
fun SarimCheckboxRow(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  label: String,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  testTag: String = "checkbox_row",
) {
  val palette = LocalSarimPalette.current
  Row(
    modifier = modifier
      .fillMaxWidth()
      .defaultMinSize(minHeight = 48.dp)
      .clip(RoundedCornerShape(10.dp))
      .testTag(testTag)
      .clickable(enabled = enabled) { onCheckedChange(!checked) }
      .padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Checkbox(
      checked = checked,
      onCheckedChange = if (enabled) onCheckedChange else null,
      enabled = enabled,
      colors = CheckboxDefaults.colors(
        checkedColor = palette.green,
        uncheckedColor = palette.textMuted,
        checkmarkColor = Color(0xFF03210E),
      ),
    )
    Text(label, color = palette.textPrimary, fontSize = 13.5.sp)
  }
}

@Composable
fun SarimEmptyState(
  title: String,
  subtitle: String,
  modifier: Modifier = Modifier,
) {
  val palette = LocalSarimPalette.current
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = 28.dp, horizontal = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(title, color = palette.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, textAlign = TextAlign.Center)
    Text(subtitle, color = palette.textMuted, fontSize = 12.5.sp, textAlign = TextAlign.Center)
  }
}

@Composable
fun InitialsAvatar(
  name: String,
  color: Color,
  sizeDp: Dp = 40.dp,
  badgeText: String = "",
) {
  val palette = LocalSarimPalette.current
  Box(contentAlignment = Alignment.BottomEnd) {
    Box(
      modifier = Modifier
        .size(sizeDp)
        .clip(CircleShape)
        .background(color.copy(alpha = 0.2f))
        .border(1.dp, color.copy(alpha = 0.65f), CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = SarimFormat.initials(name),
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = (sizeDp.value * 0.36f).sp,
      )
    }
    if (badgeText.isNotBlank()) {
      Surface(
        shape = RoundedCornerShape(6.dp),
        color = palette.amber,
      ) {
        Text(
          text = badgeText,
          color = Color(0xFF1A1000),
          fontSize = 8.5.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
      }
    }
  }
}
