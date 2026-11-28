package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
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
internal fun sarimTonalSurface(base: Color, accent: Color?, tintAmount: Float = 0.10f): Color =
  if (accent != null) lerp(base, accent, tintAmount) else base

@Composable
fun SarimCard(
  modifier: Modifier = Modifier,
  borderColor: Color? = null,
  containerColor: Color? = null,
  tonalAccent: Color? = null,
  contentPadding: Dp = 16.dp,
  content: @Composable ColumnScope.() -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  val resolvedColor = containerColor ?: sarimTonalSurface(scheme.surfaceContainer, tonalAccent, 0.10f)
  // Expressive cards are tonal, large-radius surfaces; an outline appears only when a caller
  // explicitly asks for one (e.g. to flag an alert state).
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.largeIncreased,
    color = resolvedColor,
    border = borderColor?.let { BorderStroke(1.5.dp, it) },
    tonalElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.padding(contentPadding),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      content = content,
    )
  }
}

/**
 * Material 3 Expressive collapsible card for non-entry / summary / analytics / history cards.
 * Collapsed by default (`initiallyExpanded = false`) with a spring-animated chevron, tonal surface
 * shift, >= 48.dp touch target header, and smooth vertical reveal.
 */
@Composable
fun SarimCollapsibleCard(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String = "",
  badgeText: String = "",
  badgeColor: Color? = null,
  leadingIcon: ImageVector? = null,
  iconTint: Color? = null,
  borderColor: Color? = null,
  containerColor: Color? = null,
  tonalAccent: Color? = null,
  contentPadding: Dp = 16.dp,
  initiallyExpanded: Boolean = false,
  headerActions: (@Composable RowScope.() -> Unit)? = null,
  collapsedSummary: (@Composable ColumnScope.() -> Unit)? = null,
  headerTestTag: String? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val palette = LocalSarimPalette.current
  val scheme = MaterialTheme.colorScheme
  var expanded by remember { mutableStateOf(initiallyExpanded) }
  val headerSource = remember { MutableInteractionSource() }
  val (_, pressCorner) = rememberExpressivePress(headerSource, restCorner = 28.dp, pressedCorner = 18.dp, pressedScale = 0.99f)
  val cardShape = RoundedCornerShape(pressCorner)
  val targetBg = containerColor ?: sarimTonalSurface(
    scheme.surfaceContainer,
    tonalAccent,
    if (expanded) 0.12f else 0.08f,
  )
  val resolvedColor by animateColorAsState(
    targetValue = targetBg,
    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
    label = "collapsibleCardBg",
  )
  val chevronRot by animateFloatAsState(
    targetValue = if (expanded) 0f else -90f,
    animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
    label = "collapsibleChevron",
  )
  val accentColor = badgeColor ?: tonalAccent ?: palette.cyan

  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = cardShape,
    color = resolvedColor,
    border = borderColor?.let { BorderStroke(1.5.dp, it) },
    tonalElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.padding(contentPadding),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .defaultMinSize(minHeight = 48.dp)
          .clip(RoundedCornerShape(18.dp))
          .let { m -> if (headerTestTag != null) m.testTag(headerTestTag) else m }
          .clickable(interactionSource = headerSource, indication = LocalIndication.current) {
            expanded = !expanded
          }
          .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (leadingIcon != null) {
          Surface(
            shape = CircleShape,
            color = lerp(scheme.surfaceContainerHigh, iconTint ?: accentColor, 0.18f),
            modifier = Modifier.size(36.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = iconTint ?: accentColor,
                modifier = Modifier.size(18.dp),
              )
            }
          }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = title,
            color = palette.textPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          if (subtitle.isNotBlank()) {
            Text(
              text = subtitle,
              color = palette.textMuted,
              fontSize = 11.8.sp,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        if (badgeText.isNotBlank()) {
          SarimChip(badgeText, accentColor)
        }
        if (headerActions != null) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = headerActions,
          )
        }
        Icon(
          imageVector = Icons.Rounded.ExpandMore,
          contentDescription = if (expanded) "Collapse $title" else "Expand $title",
          tint = palette.textMuted,
          modifier = Modifier.size(22.dp).rotate(chevronRot),
        )
      }

      if (!expanded && collapsedSummary != null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = collapsedSummary)
      }

      AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(animationSpec = tween(180)) + expandVertically(
          animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        ),
        exit = fadeOut(animationSpec = tween(140)) + shrinkVertically(
          animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium),
        ),
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.40f))
          content()
        }
      }
    }
  }
}

/**
 * Variant of [SarimCollapsibleCard] accepting a custom header row for cards with avatars,
 * multi-metric headers, or specialized badges.
 */
@Composable
fun SarimCollapsibleHeaderCard(
  modifier: Modifier = Modifier,
  borderColor: Color? = null,
  containerColor: Color? = null,
  tonalAccent: Color? = null,
  contentPadding: Dp = 16.dp,
  initiallyExpanded: Boolean = false,
  collapseDescription: String = "Section",
  headerContent: @Composable RowScope.(expanded: Boolean) -> Unit,
  content: @Composable ColumnScope.() -> Unit,
) {
  val palette = LocalSarimPalette.current
  val scheme = MaterialTheme.colorScheme
  var expanded by remember { mutableStateOf(initiallyExpanded) }
  val headerSource = remember { MutableInteractionSource() }
  val (_, pressCorner) = rememberExpressivePress(headerSource, restCorner = 28.dp, pressedCorner = 18.dp, pressedScale = 0.99f)
  val cardShape = RoundedCornerShape(pressCorner)
  val targetBg = containerColor ?: sarimTonalSurface(
    scheme.surfaceContainer,
    tonalAccent,
    if (expanded) 0.12f else 0.08f,
  )
  val resolvedColor by animateColorAsState(
    targetValue = targetBg,
    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
    label = "collapsibleHeaderCardBg",
  )
  val chevronRot by animateFloatAsState(
    targetValue = if (expanded) 0f else -90f,
    animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
    label = "collapsibleHeaderChevron",
  )

  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = cardShape,
    color = resolvedColor,
    border = borderColor?.let { BorderStroke(1.5.dp, it) },
    tonalElevation = 0.dp,
  ) {
    Column(
      modifier = Modifier.padding(contentPadding),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .defaultMinSize(minHeight = 48.dp)
          .clip(RoundedCornerShape(18.dp))
          .clickable(interactionSource = headerSource, indication = LocalIndication.current) {
            expanded = !expanded
          }
          .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        headerContent(expanded)
        Icon(
          imageVector = Icons.Rounded.ExpandMore,
          contentDescription = if (expanded) "Collapse $collapseDescription" else "Expand $collapseDescription",
          tint = palette.textMuted,
          modifier = Modifier.size(22.dp).rotate(chevronRot),
        )
      }

      AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(animationSpec = tween(180)) + expandVertically(
          animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
        ),
        exit = fadeOut(animationSpec = tween(140)) + shrinkVertically(
          animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium),
        ),
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.40f))
          content()
        }
      }
    }
  }
}

/** Spring-driven press feedback: the control squishes slightly and its corners tighten. */
@Composable
internal fun rememberExpressivePress(
  source: MutableInteractionSource,
  restCorner: Dp,
  pressedCorner: Dp,
  pressedScale: Float = 0.965f,
): Pair<Float, Dp> {
  val pressed by source.collectIsPressedAsState()
  val scale by animateFloatAsState(
    targetValue = if (pressed) pressedScale else 1f,
    animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
    label = "pressScale",
  )
  val corner by animateDpAsState(
    targetValue = if (pressed) pressedCorner else restCorner,
    animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
    label = "pressCorner",
  )
  return scale to corner
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
  val scheme = MaterialTheme.colorScheme
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(28.dp),
    color = scheme.surfaceContainerLow,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(4.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      options.forEach { opt ->
        val isSelected = opt == selected
        val accent = colorOf?.invoke(opt) ?: palette.green
        val source = remember { MutableInteractionSource() }
        val (scale, corner) = rememberExpressivePress(source, restCorner = 24.dp, pressedCorner = 14.dp)
        val pillShape = RoundedCornerShape(corner)
        Surface(
          modifier = Modifier
            .weight(1f)
            .defaultMinSize(minHeight = 48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(pillShape)
            .testTag("${testTagPrefix}_${labelOf(opt).lowercase().replace(" ", "_")}")
            .clickable(interactionSource = source, indication = LocalIndication.current) { onSelect(opt) },
          shape = pillShape,
          color = if (isSelected) lerp(scheme.surfaceContainerHigh, accent, 0.24f) else scheme.surfaceContainerHigh.copy(alpha = 0.72f),
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

/**
 * One size for every input control (text, number, date, dropdown, search).
 * The calendar/date field is the reference; all other fields use these same values.
 */
object SarimFieldDefaults {
  val MinHeight = 48.dp
  val HorizontalPadding = 14.dp
  val TrailingPadding = 8.dp
  val CornerRadius = 16.dp
  val Shape = RoundedCornerShape(CornerRadius)
  val BorderWidth = 1.dp
  val TextSize = 14.sp
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
  val interaction = remember { MutableInteractionSource() }
  val focused by interaction.collectIsFocusedAsState()
  val clickable = readOnly && onClickIfReadOnly != null
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (label.isNotBlank()) SarimFieldLabel(label)
    val borderColor = when {
      focused -> palette.green
      dashedReadOnly -> palette.cyan.copy(alpha = 0.45f)
      else -> palette.border
    }
    val isNumeric = keyboardType == KeyboardType.Decimal || keyboardType == KeyboardType.Number || keyboardType == KeyboardType.Phone
    BasicTextField(
      value = value,
      onValueChange = { if (it.length <= maxLength) onValueChange(it) },
      modifier = textFieldModifier
        .fillMaxWidth()
        .testTag(testTag)
        .let { m -> if (clickable) m.clickable { onClickIfReadOnly?.invoke() } else m },
      enabled = !clickable,
      readOnly = readOnly,
      singleLine = true,
      interactionSource = interaction,
      cursorBrush = SolidColor(palette.green),
      visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
      keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
      keyboardActions = KeyboardActions.Default,
      textStyle = TextStyle(
        fontFamily = if (isNumeric) ManropeFontFamily else PlusJakartaFontFamily,
        fontFeatureSettings = "tnum",
        fontSize = SarimFieldDefaults.TextSize,
        fontWeight = FontWeight.SemiBold,
        color = palette.textPrimary,
      ),
      decorationBox = { inner ->
        val scheme = MaterialTheme.colorScheme
        val border = when {
          focused -> BorderStroke(1.5.dp, palette.green)
          dashedReadOnly -> BorderStroke(1.dp, palette.cyan.copy(alpha = 0.45f))
          else -> null
        }
        Surface(
          shape = SarimFieldDefaults.Shape,
          color = if (focused) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh,
          border = border,
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .defaultMinSize(minHeight = SarimFieldDefaults.MinHeight)
              .padding(
                start = SarimFieldDefaults.HorizontalPadding,
                end = if (trailingContent != null) SarimFieldDefaults.TrailingPadding else SarimFieldDefaults.HorizontalPadding,
              ),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (leadingIcon != null) {
              Icon(leadingIcon, contentDescription = null, tint = palette.textMuted, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(8.dp))
            }
            if (prefixText != null) {
              Text("$prefixText ", color = palette.textMuted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
              if (value.isEmpty() && placeholder.isNotBlank()) {
                Text(placeholder, color = palette.textMuted.copy(alpha = 0.65f), fontSize = SarimFieldDefaults.TextSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
              inner()
            }
            if (suffixText != null) {
              Text(" $suffixText", color = palette.textMuted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            trailingContent?.invoke()
          }
        }
      },
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
                  Icons.Rounded.Close,
                  contentDescription = "Clear text",
                  tint = palette.textMuted,
                  modifier = Modifier.size(16.dp),
                )
              }
            }
            IconButton(
              onClick = {
                filterActive = false
                showDropdown = !isMenuVisible
              },
              modifier = Modifier.size(40.dp).testTag("${testTag}_toggle"),
            ) {
              Icon(
                Icons.Rounded.ArrowDropDown,
                contentDescription = if (isMenuVisible) "Hide options" else "Show options",
                tint = palette.textMuted,
                modifier = Modifier.size(24.dp).rotate(if (isMenuVisible) 180f else 0f),
              )
            }
          }
        },
        textFieldModifier = Modifier
          .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = true)
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
          .exposedDropdownSize(matchAnchorWidth = true)
          .heightIn(max = 280.dp)
          .clip(RoundedCornerShape(20.dp))
          .background(MaterialTheme.colorScheme.surfaceContainerHigh),
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
                  Icon(Icons.Rounded.Check, contentDescription = null, tint = palette.green, modifier = Modifier.size(16.dp))
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
  val density = LocalDensity.current
  var expanded by remember { mutableStateOf(false) }
  var anchorWidthPx by remember { mutableIntStateOf(0) }
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (label.isNotBlank()) SarimFieldLabel(label)
    Box(modifier = Modifier.fillMaxWidth()) {
      // One tap handler only: the arrow and the whole field toggle the menu.
      val scheme = MaterialTheme.colorScheme
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .onSizeChanged { anchorWidthPx = it.width }
          .defaultMinSize(minHeight = SarimFieldDefaults.MinHeight)
          .clip(SarimFieldDefaults.Shape)
          .testTag(testTag)
          .clickable { expanded = !expanded },
        shape = SarimFieldDefaults.Shape,
        color = if (expanded) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh,
        border = if (expanded) BorderStroke(1.5.dp, palette.green) else null,
      ) {
        Row(
          modifier = Modifier
            .defaultMinSize(minHeight = SarimFieldDefaults.MinHeight)
            .padding(start = SarimFieldDefaults.HorizontalPadding, end = SarimFieldDefaults.TrailingPadding),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = selectedText,
            color = palette.textPrimary,
            fontSize = SarimFieldDefaults.TextSize,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Icon(
            Icons.Rounded.ArrowDropDown,
            contentDescription = if (expanded) "Hide options" else "Show options",
            tint = palette.textMuted,
            modifier = Modifier.size(24.dp).rotate(if (expanded) 180f else 0f),
          )
        }
      }
      DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        modifier = Modifier
          .width(with(density) { anchorWidthPx.toDp() })
          .heightIn(max = 320.dp)
          .clip(RoundedCornerShape(20.dp))
          .background(MaterialTheme.colorScheme.surfaceContainerHigh),
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
                  Icon(Icons.Rounded.Check, contentDescription = null, tint = palette.green, modifier = Modifier.size(16.dp))
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

/** Every button (primary, secondary, danger, dialog actions) shares one size so pairs line up in a row. */
object SarimButtonDefaults {
  val Height = 48.dp
  val CornerRadius = 24.dp
  val HorizontalPadding = 14.dp
  val TextSize = 14.sp
  val RowSpacing = 10.dp
}

@Composable
fun SarimPrimaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  testTag: String = "primary_button",
) {
  val scheme = MaterialTheme.colorScheme
  val source = remember { MutableInteractionSource() }
  val (scale, corner) = rememberExpressivePress(source, restCorner = SarimButtonDefaults.CornerRadius, pressedCorner = 12.dp)
  val shape = RoundedCornerShape(corner)
  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(SarimButtonDefaults.Height)
      .graphicsLayer { scaleX = scale; scaleY = scale }
      .clip(shape)
      .background(if (enabled) scheme.primary else scheme.onSurface.copy(alpha = 0.12f), shape)
      .testTag(testTag)
      .clickable(
        interactionSource = source,
        indication = LocalIndication.current,
        enabled = enabled,
        onClick = onClick,
      ),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      color = if (enabled) scheme.onPrimary else scheme.onSurface.copy(alpha = 0.38f),
      fontWeight = FontWeight.Bold,
      fontSize = SarimButtonDefaults.TextSize,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(horizontal = SarimButtonDefaults.HorizontalPadding),
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
  val scheme = MaterialTheme.colorScheme
  val source = remember { MutableInteractionSource() }
  val (scale, corner) = rememberExpressivePress(source, restCorner = SarimButtonDefaults.CornerRadius, pressedCorner = 12.dp)
  val shape = RoundedCornerShape(corner)
  Box(
    modifier = modifier
      .height(SarimButtonDefaults.Height)
      .graphicsLayer { scaleX = scale; scaleY = scale }
      .clip(shape)
      .background(scheme.secondaryContainer, shape)
      .testTag(testTag)
      .clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = SarimButtonDefaults.HorizontalPadding),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (icon != null) {
        Icon(icon, contentDescription = null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
      }
      Text(text, color = scheme.onSecondaryContainer, fontSize = SarimButtonDefaults.TextSize, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
  val scheme = MaterialTheme.colorScheme
  val source = remember { MutableInteractionSource() }
  val (scale, corner) = rememberExpressivePress(source, restCorner = SarimButtonDefaults.CornerRadius, pressedCorner = 12.dp)
  val shape = RoundedCornerShape(corner)
  val content = if (enabled) palette.red else palette.textMuted
  Box(
    modifier = modifier
      .height(SarimButtonDefaults.Height)
      .graphicsLayer { scaleX = scale; scaleY = scale }
      .clip(shape)
      .background(if (enabled) palette.red.copy(alpha = 0.18f) else scheme.onSurface.copy(alpha = 0.08f), shape)
      .testTag(testTag)
      .clickable(interactionSource = source, indication = LocalIndication.current, enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = SarimButtonDefaults.HorizontalPadding),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (icon != null) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
      }
      Text(text, color = content, fontSize = SarimButtonDefaults.TextSize, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
  val scheme = MaterialTheme.colorScheme
  val container = sarimTonalSurface(scheme.surfaceContainerHigh, accentColor, 0.14f)
  Surface(
    modifier = modifier,
    shape = MaterialTheme.shapes.large,
    color = container,
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = label.uppercase(),
        color = palette.textMuted,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = value,
        color = accentColor ?: palette.textPrimary,
        style = TabularNumberStyle.copy(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (!subText.isNullOrBlank()) {
        Text(
          text = subText,
          color = palette.textMuted,
          fontSize = 11.sp,
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
  val scheme = MaterialTheme.colorScheme
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(50),
    color = lerp(scheme.surfaceContainerHighest, color, 0.20f),
  ) {
    Text(
      text = text,
      color = color,
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
      maxLines = 1,
    )
  }
}

@Composable
fun SarimCallout(
  text: String,
  toneColor: Color,
  modifier: Modifier = Modifier,
  icon: ImageVector? = Icons.Rounded.Warning,
) {
  val scheme = MaterialTheme.colorScheme
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.large,
    color = lerp(scheme.surfaceContainerHigh, toneColor, 0.14f),
  ) {
    Row(
      modifier = Modifier.padding(16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (icon != null) {
        Icon(icon, contentDescription = null, tint = toneColor, modifier = Modifier.size(20.dp))
      }
      Text(text, color = toneColor, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
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
      .clip(RoundedCornerShape(14.dp))
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
  val scheme = MaterialTheme.colorScheme
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.largeIncreased,
    color = scheme.surfaceContainerLow,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 26.dp, horizontal = 20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Surface(
        shape = CircleShape,
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.size(44.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Rounded.Inbox,
            contentDescription = null,
            tint = palette.textMuted,
            modifier = Modifier.size(20.dp),
          )
        }
      }
      Text(title, color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp, textAlign = TextAlign.Center)
      Text(subtitle, color = palette.textMuted, fontSize = 12.5.sp, textAlign = TextAlign.Center)
    }
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
  val scheme = MaterialTheme.colorScheme
  Box(contentAlignment = Alignment.BottomEnd) {
    Box(
      modifier = Modifier
        .size(sizeDp)
        .clip(CircleShape)
        .background(lerp(scheme.surfaceContainerHighest, color, 0.22f))
        .border(1.5.dp, color.copy(alpha = 0.45f), CircleShape),
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
        shape = RoundedCornerShape(50),
        color = palette.amber,
      ) {
        Text(
          text = badgeText,
          color = Color(0xFF1A1000),
          fontSize = 8.5.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
      }
    }
  }
}
