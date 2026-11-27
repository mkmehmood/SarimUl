package com.example.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
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
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val SPLASH_QUOTES: List<Pair<String, String>> = listOf(
  "The details are not the details. They make the design." to "Charles Eames",
  "Perfection is achieved not when there is nothing more to add, but when there is nothing left to take away." to "Antoine de Saint-Exupéry",
  "Price is what you pay. Value is what you get." to "Warren Buffett",
  "It is not the strongest of the species that survives, but the one most adaptable to change." to "Leon C. Megginson",
  "An investment in knowledge pays the best interest." to "Benjamin Franklin",
  "What gets measured gets managed." to "Peter Drucker",
  "The purpose of a business is to create a customer who creates customers." to "Shiv Singh",
  "Simplicity is the ultimate sophistication." to "Leonardo da Vinci",
  "Opportunities don't happen. You create them." to "Chris Grosser",
  "The best way to predict the future is to create it." to "Peter Drucker",
  "Chase the vision, not the money. The money will end up following you." to "Tony Hsieh",
  "Build something 100 people love, not something 1 million people kind of like." to "Brian Chesky",
  "Excellence is not an act but a habit." to "Will Durant",
  "Your most unhappy customers are your greatest source of learning." to "Bill Gates",
  "Well done is better than well said." to "Benjamin Franklin",
  "Honesty is the first chapter in the book of wisdom." to "Thomas Jefferson",
  "It takes twenty years to build a reputation and five minutes to ruin it." to "Warren Buffett",
  "Do not save what is left after spending; spend what is left after saving." to "Warren Buffett",
  "Be fearful when others are greedy, and greedy when others are fearful." to "Warren Buffett",
  "Risk comes from not knowing what you are doing." to "Warren Buffett",
  "A penny saved is a penny earned." to "Benjamin Franklin",
  "Beware of little expenses; a small leak will sink a great ship." to "Benjamin Franklin",
  "The best advertisement is a satisfied customer." to "Philip Kotler",
  "Efficiency is doing things right; effectiveness is doing the right things." to "Peter Drucker",
  "Quality means doing it right when no one is looking." to "Henry Ford",
  "Coming together is a beginning; keeping together is progress; working together is success." to "Henry Ford",
  "Whether you think you can or you think you can't, you're right." to "Henry Ford",
  "Genius is one percent inspiration and ninety-nine percent perspiration." to "Thomas Edison",
  "There is no substitute for hard work." to "Thomas Edison",
  "Success is the sum of small efforts, repeated day in and day out." to "Robert Collier",
  "Small deeds done are better than great deeds planned." to "Peter Marshall",
  "Start where you are. Use what you have. Do what you can." to "Arthur Ashe",
  "The only way to do great work is to love what you do." to "Steve Jobs",
  "Trust is the glue of life." to "Stephen Covey",
  "Customers don't expect you to be perfect. They expect you to fix things when they go wrong." to "Donald Porter",
  "A journey of a thousand miles begins with a single step." to "Lao Tzu",
  "Do the difficult things while they are easy and the great things while they are small." to "Lao Tzu",
  "Knowing is not enough; we must apply." to "Johann Wolfgang von Goethe",
  "It always seems impossible until it is done." to "Nelson Mandela",
  "Courage is not the absence of fear, but the triumph over it." to "Nelson Mandela",
  "Discipline is the bridge between goals and accomplishment." to "Jim Rohn",
  "Nothing will work unless you do." to "Maya Angelou",
  "Make each day your masterpiece." to "John Wooden",
  "Quality is never an accident; it is always the result of intelligent effort." to "John Ruskin",
  "Little by little, one travels far." to "J.R.R. Tolkien",
  "Slow and steady wins the race." to "Aesop",
  "No act of kindness, no matter how small, is ever wasted." to "Aesop",
  "Well begun is half done." to "Aristotle",
  "The best time to plant a tree was twenty years ago. The second best time is now." to "Chinese Proverb",
  "If you want to go fast, go alone. If you want to go far, go together." to "African Proverb",
  "Fall seven times, stand up eight." to "Japanese Proverb",
  "Never let the fear of striking out keep you from playing the game." to "Babe Ruth",
  "The expert in anything was once a beginner." to "Helen Hayes",
  "A smooth sea never made a skilled sailor." to "Franklin D. Roosevelt",
  "Do what you can, with what you have, where you are." to "Theodore Roosevelt",
  "Change your thoughts and you change your world." to "Norman Vincent Peale",
  "Whoever is happy will make others happy too." to "Anne Frank",
  "He who has a why to live can bear almost any how." to "Friedrich Nietzsche",
  "Innovation distinguishes between a leader and a follower." to "Steve Jobs",
  "The way to get started is to quit talking and begin doing." to "Walt Disney",
)

@Composable
fun SplashScreen(onFinished: () -> Unit) {
  val palette = LocalSarimPalette.current
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  val selectedQuote = remember { SPLASH_QUOTES.random() }

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

  val cal = remember(now) { Calendar.getInstance().apply { timeInMillis = now } }
  val hr = cal.get(Calendar.HOUR_OF_DAY)
  val mn = cal.get(Calendar.MINUTE)
  val hourFrac = hr + mn / 60f
  val isDay = hourFrac >= 6f && hourFrac < 18f
  val dialProgress = if (isDay) (hourFrac - 6f) / 12f else ((hourFrac - 18f + 24f) % 24f) / 12f

  val greeting = when {
    hr < 5 -> "EARLY HOURS"
    hr < 12 -> "GOOD MORNING"
    hr < 17 -> "GOOD AFTERNOON"
    else -> "GOOD EVENING"
  }

  val periodGlow = when {
    hr < 5 -> Color(0xFF6366F1)
    hr < 8 -> Color(0xFFF59E0B)
    hr < 16 -> Color(0xFF10B981)
    hr < 18 -> Color(0xFFF97316)
    else -> Color(0xFF6366F1)
  }

  val clockDigits = remember(now) {
    val h12 = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
    "%d:%02d".format(h12, mn)
  }
  val amPm = if (cal.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
  val dateStr = remember(now) {
    val dayFmt = SimpleDateFormat("EEEE", Locale.US).format(Date(now)).uppercase(Locale.US)
    val monthFmt = SimpleDateFormat("d MMMM", Locale.US).format(Date(now)).uppercase(Locale.US)
    "$dayFmt · $monthFmt"
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(
          listOf(Color(0xFF060A08), Color(0xFF0B1C14), Color(0xFF060A08))
        )
      )
      .clickable { onFinished() }
      .systemBarsPadding()
      .padding(horizontal = 24.dp, vertical = 22.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxSize(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.SpaceBetween,
    ) {
      Spacer(Modifier.height(12.dp))

      // Semi-circular Day/Night Solar-Lunar Dial
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 330.dp)
            .height(210.dp),
          contentAlignment = Alignment.BottomCenter,
        ) {
          Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = min(size.width * 0.42f, size.height * 0.78f)
            val cx = size.width / 2f
            val cy = size.height * 0.84f

            // Subtle ambient dome glow
            drawCircle(
              brush = Brush.radialGradient(
                listOf(periodGlow.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(cx, cy - radius * 0.35f),
                radius = radius * 1.15f,
              ),
              center = Offset(cx, cy - radius * 0.35f),
              radius = radius * 1.15f,
            )

            // 13 radial ticks along upper semicircle
            for (k in 0..12) {
              val ang = PI * (1.0 - k / 12.0)
              val tickInner = radius * 1.07f
              val tickOuter = radius * (if (k == 0 || k == 6 || k == 12) 1.16f else 1.11f)
              drawLine(
                color = Color.White.copy(alpha = if (k == 6) 0.45f else 0.24f),
                start = Offset(cx + (tickInner * cos(ang)).toFloat(), cy - (tickInner * sin(ang)).toFloat()),
                end = Offset(cx + (tickOuter * cos(ang)).toFloat(), cy - (tickOuter * sin(ang)).toFloat()),
                strokeWidth = if (k == 6) 2.dp.toPx() else 1.2.dp.toPx(),
                cap = StrokeCap.Round,
              )
            }

            // Dotted track upper semicircle (from 180 deg to 360 deg = sweep +180)
            val arcTopLeft = Offset(cx - radius, cy - radius)
            val arcSize = Size(radius * 2f, radius * 2f)
            drawArc(
              color = Color.White.copy(alpha = 0.28f),
              startAngle = 180f,
              sweepAngle = 180f,
              useCenter = false,
              topLeft = arcTopLeft,
              size = arcSize,
              style = Stroke(
                width = 1.6.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 9f), 0f),
              ),
            )

            // Active progress glow arc
            drawArc(
              color = palette.green.copy(alpha = 0.55f),
              startAngle = 180f,
              sweepAngle = 180f * dialProgress.coerceIn(0.01f, 1f),
              useCenter = false,
              topLeft = arcTopLeft,
              size = arcSize,
              style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )

            // Sun or Crescent Moon orb
            val orbAng = PI * (1.0 - dialProgress.coerceIn(0f, 1f))
            val ox = cx + (radius * cos(orbAng)).toFloat()
            val oy = cy - (radius * sin(orbAng)).toFloat()
            drawCircle(
              brush = Brush.radialGradient(
                listOf(
                  (if (isDay) Color(0xFFFBBF24) else Color(0xFFE2E8F0)).copy(alpha = 0.45f),
                  Color.Transparent,
                ),
                center = Offset(ox, oy),
                radius = 28.dp.toPx(),
              ),
              center = Offset(ox, oy),
              radius = 28.dp.toPx(),
            )
            if (isDay) {
              drawCircle(color = Color(0xFFF59E0B), radius = 11.dp.toPx(), center = Offset(ox, oy))
              drawCircle(color = Color(0xFFFEF08A), radius = 6.dp.toPx(), center = Offset(ox, oy))
            } else {
              drawCircle(color = Color(0xFFE2E8F0), radius = 10.5.dp.toPx(), center = Offset(ox, oy))
              drawCircle(
                color = Color(0xFF14211D),
                radius = 9.dp.toPx(),
                center = Offset(ox + 4.dp.toPx(), oy - 3.dp.toPx()),
              )
            }
          }

          Column(
            modifier = Modifier.padding(bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
          ) {
            Text(
              text = greeting,
              color = palette.green,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              letterSpacing = 2.sp,
            )
            Row(
              verticalAlignment = Alignment.Bottom,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Text(
                text = clockDigits,
                color = Color.White,
                style = TabularNumberStyle.copy(fontSize = 36.sp, fontWeight = FontWeight.Medium),
              )
              Text(
                text = amPm,
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 6.dp),
              )
            }
            Text(
              text = dateStr,
              color = Color.White.copy(alpha = 0.72f),
              fontSize = 10.5.sp,
              letterSpacing = 1.4.sp,
            )
          }
        }

        // Left / right dial horizon numbers ("06"/"18" or "18"/"06")
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 310.dp)
            .padding(horizontal = 24.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = if (isDay) "06" else "18",
            color = Color.White.copy(alpha = 0.85f),
            style = TabularNumberStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
          )
          Text(
            text = if (isDay) "18" else "06",
            color = Color.White.copy(alpha = 0.85f),
            style = TabularNumberStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
          )
        }
      }

      // Brand title + glowing green dot divider + random quote
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 12.dp),
      ) {
        Text(
          text = "GULL AND ZUBAIR",
          color = Color.White,
          fontFamily = CinzelFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 23.sp,
          letterSpacing = 2.6.sp,
          textAlign = TextAlign.Center,
        )
        Text(
          text = "NASWAR DEALERS",
          color = palette.green,
          fontSize = 11.5.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 2.4.sp,
        )

        Spacer(Modifier.height(4.dp))

        Box(
          modifier = Modifier.fillMaxWidth(0.55f),
          contentAlignment = Alignment.Center,
        ) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(1.dp)
              .background(
                Brush.horizontalGradient(
                  listOf(Color.Transparent, palette.green.copy(alpha = 0.4f), Color.Transparent)
                )
              ),
          )
          Box(
            modifier = Modifier
              .size(16.dp)
              .clip(CircleShape)
              .background(palette.green.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
          ) {
            Box(
              modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(palette.green),
            )
          }
        }

        Spacer(Modifier.height(6.dp))

        Text(
          text = "\"${selectedQuote.first}\"",
          color = Color.White.copy(alpha = 0.88f),
          fontFamily = PlayfairDisplayFontFamily,
          fontStyle = FontStyle.Italic,
          fontSize = 15.sp,
          lineHeight = 22.sp,
          textAlign = TextAlign.Center,
        )
        Text(
          text = "— ${selectedQuote.second.uppercase(Locale.US)}",
          color = Color.White.copy(alpha = 0.56f),
          fontSize = 10.5.sp,
          letterSpacing = 1.6.sp,
        )
      }

      // Bottom footer dot + PREPARING YOUR DAY
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 6.dp),
      ) {
        Box(
          modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(palette.green.copy(alpha = 0.22f)),
          contentAlignment = Alignment.Center,
        ) {
          Box(
            modifier = Modifier
              .size(6.dp)
              .clip(CircleShape)
              .background(palette.green),
          )
        }
        Text(
          text = "PREPARING YOUR DAY",
          color = Color.White.copy(alpha = 0.78f),
          fontSize = 10.5.sp,
          fontWeight = FontWeight.Medium,
          letterSpacing = 1.8.sp,
        )
      }
    }
  }
}
