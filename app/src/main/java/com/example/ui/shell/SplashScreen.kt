package com.example.ui.shell

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CinzelFontFamily
import com.example.ui.theme.LocalSarimPalette
import com.example.ui.theme.PlayfairDisplayFontFamily
import com.example.ui.theme.TabularNumberStyle
import com.example.util.SarimFormat
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SplashScreen(onFinished: () -> Unit) {
  val palette = LocalSarimPalette.current
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

  LaunchedEffect(Unit) {
    delay(1500)
    onFinished()
  }

  LaunchedEffect(Unit) {
    while (true) {
      delay(1000)
      now = System.currentTimeMillis()
    }
  }

  val hour = remember(now) { Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY) }
  val greeting = when {
    hour < 12 -> "Good Morning"
    hour < 17 -> "Good Afternoon"
    else -> "Good Evening"
  }

  val infinite = rememberInfiniteTransition(label = "dial")
  val rotation by infinite.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing)),
    label = "dial_rot",
  )

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(
          listOf(Color(0xFF000000), Color(0xFF031A0E), Color(0xFF000000))
        )
      )
      .clickable { onFinished() }
      .systemBarsPadding()
      .padding(24.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxSize(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.SpaceBetween,
    ) {
      Spacer(Modifier.height(16.dp))

      // Decorative dial graphic
      Box(modifier = Modifier.size(210.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
          val radius = size.minDimension / 2f
          val center = Offset(size.width / 2f, size.height / 2f)
          drawCircle(
            color = palette.green.copy(alpha = 0.22f),
            radius = radius,
            style = Stroke(width = 2.dp.toPx()),
          )
          drawCircle(
            color = palette.cyan.copy(alpha = 0.18f),
            radius = radius * 0.78f,
            style = Stroke(width = 1.5.dp.toPx()),
          )
          for (i in 0 until 24) {
            val angle = Math.toRadians((i * 15.0) + rotation)
            val inner = radius * 0.85f
            val outer = if (i % 2 == 0) radius * 0.96f else radius * 0.91f
            drawLine(
              color = if (i % 6 == 0) palette.green else palette.green.copy(alpha = 0.35f),
              start = Offset(center.x + (inner * cos(angle)).toFloat(), center.y + (inner * sin(angle)).toFloat()),
              end = Offset(center.x + (outer * cos(angle)).toFloat(), center.y + (outer * sin(angle)).toFloat()),
              strokeWidth = if (i % 6 == 0) 2.5.dp.toPx() else 1.2.dp.toPx(),
            )
          }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(greeting, color = palette.green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
          Text(
            SarimFormat.formatClock(now),
            color = palette.textPrimary,
            style = TabularNumberStyle.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold),
          )
          Text(SarimFormat.formatDate(now), color = palette.textMuted, fontSize = 11.5.sp)
        }
      }

      Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          "GULL AND ZUBAIR",
          color = palette.textPrimary,
          fontFamily = CinzelFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 24.sp,
          letterSpacing = 2.5.sp,
          textAlign = TextAlign.Center,
        )
        Text(
          "Naswar Dealers",
          color = palette.green,
          fontFamily = PlayfairDisplayFontFamily,
          fontSize = 16.sp,
          fontStyle = FontStyle.Italic,
        )
        Spacer(Modifier.height(12.dp))
        Text(
          "“Honest measure and clear ledgers build a house that endures.”",
          color = palette.textMuted,
          fontSize = 13.sp,
          fontStyle = FontStyle.Italic,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text("— Traditional Guild Maxim", color = palette.textMuted.copy(alpha = 0.7f), fontSize = 11.sp)
      }

      Text(
        "Preparing your day",
        color = palette.green.copy(alpha = 0.85f),
        fontSize = 12.sp,
        letterSpacing = 1.sp,
      )
    }
  }
}
