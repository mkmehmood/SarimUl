package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LocalSarimPalette

@Composable
fun rememberSkeletonShimmerBrush(): Brush {
  val palette = LocalSarimPalette.current
  val transition = rememberInfiniteTransition(label = "skeleton_shimmer")
  val shift by transition.animateFloat(
    initialValue = -350f,
    targetValue = 950f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1250, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "skeleton_shift",
  )
  return Brush.linearGradient(
    colors = listOf(
      palette.surfaceElevated,
      palette.border.copy(alpha = 0.78f),
      palette.green.copy(alpha = 0.16f),
      palette.border.copy(alpha = 0.78f),
      palette.surfaceElevated,
    ),
    start = Offset(shift, 0f),
    end = Offset(shift + 320f, 180f),
  )
}

@Composable
fun ShimmerPlaceholder(
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(14.dp),
) {
  val brush = rememberSkeletonShimmerBrush()
  Box(
    modifier = modifier
      .clip(shape)
      .background(brush),
  )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SyncingSkeletonBanner(
  message: String,
  isSyncing: Boolean,
  modifier: Modifier = Modifier,
) {
  val palette = LocalSarimPalette.current
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    color = palette.cyan.copy(alpha = 0.14f),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      LoadingIndicator(
        modifier = Modifier.size(28.dp),
        color = palette.cyan,
      )
      Text(
        text = if (isSyncing) message else "Loading workspace data...",
        color = palette.cyan,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.weight(1f),
      )
      SarimChip(text = if (isSyncing) "SYNCING" else "LOADING", color = palette.green)
    }
  }
}

@Composable
fun InventorySkeletonContent(
  isSyncing: Boolean,
  modifier: Modifier = Modifier,
) {
  val palette = LocalSarimPalette.current
  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp)
      .testTag("inventory_skeleton_loader"),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    SyncingSkeletonBanner(
      message = "Syncing inventory with Firestore...",
      isSyncing = isSyncing,
    )

    // Header Row Skeleton
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ShimmerPlaceholder(Modifier.width(140.dp).height(26.dp))
        ShimmerPlaceholder(Modifier.width(190.dp).height(12.dp))
      }
      ShimmerPlaceholder(Modifier.width(104.dp).height(44.dp), shape = RoundedCornerShape(50))
    }

    // 2x2 KPI Stat Tile Skeletons
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      repeat(2) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          repeat(2) {
            Surface(
              modifier = Modifier.weight(1f),
              shape = RoundedCornerShape(20.dp),
              color = palette.surfaceElevated,
              border = BorderStroke(1.dp, palette.border),
            ) {
              Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                ShimmerPlaceholder(Modifier.width(88.dp).height(10.dp))
                ShimmerPlaceholder(Modifier.width(116.dp).height(20.dp))
              }
            }
          }
        }
      }
    }

    // Primary CTA Button Skeleton
    ShimmerPlaceholder(
      modifier = Modifier.fillMaxWidth().height(50.dp),
      shape = RoundedCornerShape(50),
    )

    // Search + Sort Card Skeleton
    SarimCard {
      ShimmerPlaceholder(Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp))
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) {
          ShimmerPlaceholder(Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(50))
        }
      }
    }

    // Raw Materials List Card Skeleton
    SarimCard {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        ShimmerPlaceholder(Modifier.width(124.dp).height(18.dp))
        ShimmerPlaceholder(Modifier.width(64.dp).height(22.dp), shape = RoundedCornerShape(50))
      }

      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(4) {
          Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = palette.surfaceElevated,
            border = BorderStroke(1.dp, palette.border),
          ) {
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  ShimmerPlaceholder(Modifier.size(20.dp), shape = CircleShape)
                  ShimmerPlaceholder(Modifier.width(130.dp).height(15.dp))
                }
                ShimmerPlaceholder(Modifier.width(78.dp).height(15.dp))
              }
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                ShimmerPlaceholder(Modifier.width(140.dp).height(12.dp))
                ShimmerPlaceholder(Modifier.width(92.dp).height(12.dp))
              }
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShimmerPlaceholder(Modifier.weight(1f).height(40.dp), shape = RoundedCornerShape(50))
                ShimmerPlaceholder(Modifier.weight(0.8f).height(40.dp), shape = RoundedCornerShape(50))
              }
            }
          }
        }
      }
    }

    // Purchase Lots Accordion Skeleton
    SarimCard {
      ShimmerPlaceholder(Modifier.width(168.dp).height(18.dp))
      repeat(2) { idx ->
        ShimmerPlaceholder(Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(16.dp))
        if (idx == 0) HorizontalDivider(color = palette.border)
      }
    }

    Spacer(Modifier.height(16.dp))
  }
}

@Composable
fun LedgerSkeletonContent(
  isSyncing: Boolean,
  modifier: Modifier = Modifier,
) {
  val palette = LocalSarimPalette.current
  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp)
      .testTag("ledger_skeleton_loader"),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    SyncingSkeletonBanner(
      message = "Syncing daily ledger with Firestore...",
      isSyncing = isSyncing,
    )

    // Hero Cash In Hand Card Skeleton
    SarimCard(borderColor = palette.green.copy(alpha = 0.35f)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          ShimmerPlaceholder(Modifier.width(105.dp).height(12.dp))
          ShimmerPlaceholder(Modifier.width(185.dp).height(28.dp))
        }
        ShimmerPlaceholder(Modifier.size(44.dp), shape = RoundedCornerShape(16.dp))
      }
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) {
          Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
            color = palette.surfaceElevated,
            border = BorderStroke(1.dp, palette.border),
          ) {
            Column(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              ShimmerPlaceholder(Modifier.width(46.dp).height(10.dp))
              ShimmerPlaceholder(Modifier.fillMaxWidth(0.85f).height(16.dp))
            }
          }
        }
      }
    }

    // Summary Tiles Row Skeleton
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      repeat(3) {
        Surface(
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(20.dp),
          color = palette.surfaceElevated,
          border = BorderStroke(1.dp, palette.border),
        ) {
          Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            ShimmerPlaceholder(Modifier.width(64.dp).height(10.dp))
            ShimmerPlaceholder(Modifier.fillMaxWidth(0.9f).height(17.dp))
            ShimmerPlaceholder(Modifier.width(50.dp).height(10.dp))
          }
        }
      }
    }

    // Entry Form Card Skeleton
    SarimCard {
      ShimmerPlaceholder(Modifier.width(110.dp).height(18.dp))
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) {
          ShimmerPlaceholder(Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(50))
        }
      }
      ShimmerPlaceholder(Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp))
      ShimmerPlaceholder(Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp))
      ShimmerPlaceholder(Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp))
      ShimmerPlaceholder(Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(50))
    }

    // Payment Channels Card Skeleton
    SarimCard {
      ShimmerPlaceholder(Modifier.width(142.dp).height(17.dp))
      repeat(3) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          ShimmerPlaceholder(Modifier.width(110.dp).height(14.dp))
          ShimmerPlaceholder(Modifier.width(86.dp).height(14.dp))
        }
      }
    }

    // Cashbook History Card Skeleton
    SarimCard {
      ShimmerPlaceholder(Modifier.width(168.dp).height(17.dp))
      repeat(3) {
        ShimmerPlaceholder(Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp))
      }
    }

    Spacer(Modifier.height(24.dp))
  }
}
