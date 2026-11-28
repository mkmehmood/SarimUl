package com.example.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.remember

/**
 * Web `handleTripleTap`: three taps within 600 ms on a calculator field jump to another tab.
 * Observes on the initial pass without consuming, so the field still behaves normally.
 */
fun Modifier.tripleTapTo(onTriple: () -> Unit): Modifier = composed {
  val taps = remember { ArrayDeque<Long>() }
  pointerInput(Unit) {
    awaitEachGesture {
      awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
      val up = waitForUpOrCancellation(PointerEventPass.Initial)
      if (up != null) {
        val now = System.currentTimeMillis()
        taps.addLast(now)
        while (taps.isNotEmpty() && now - taps.first() >= 600L) taps.removeFirst()
        if (taps.size >= 3) {
          taps.clear()
          onTriple()
        }
      }
    }
  }
}
