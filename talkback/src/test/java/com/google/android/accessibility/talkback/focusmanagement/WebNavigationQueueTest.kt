/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.focusmanagement

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.focusmanagement.action.NavigationAction
import com.google.android.accessibility.utils.Performance.EventId
import com.google.android.accessibility.utils.traversal.TraversalStrategy
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebNavigationQueueTest {
  private lateinit var queue: WebNavigationQueue
  private lateinit var current: AccessibilityNodeInfoCompat
  private val dispatched = ArrayList<NavigationAction>()
  private var accepted = true

  @Before fun setup() {
    current = node("item 1")
    queue = WebNavigationQueue(Handler(Looper.getMainLooper()), ::dispatch)
  }

  private fun node(text: String, window: Int = 1): AccessibilityNodeInfoCompat {
    val raw = AccessibilityNodeInfo.obtain()
    raw.text = text
    raw.packageName = "example.web"
    ReflectionHelpers.setField(raw, "mWindowId", window)
    return AccessibilityNodeInfoCompat.wrap(raw)
  }

  private fun action(backward: Boolean = false) = NavigationAction.Builder()
    .setAction(NavigationAction.DIRECTIONAL_NAVIGATION)
    .setTarget(NavigationTarget.TARGET_DEFAULT)
    .setDirection(if (backward) TraversalStrategy.SEARCH_FOCUS_BACKWARD else TraversalStrategy.SEARCH_FOCUS_FORWARD)
    .build()

  private fun dispatch(action: NavigationAction, eventId: EventId?): Boolean {
    dispatched.add(action)
    val token = queue.beforeAction(current)
    queue.actionReturned(token, accepted)
    return accepted
  }

  private fun submit(action: NavigationAction) {
    if (!queue.enqueueIfWaiting(action, null)) dispatch(action, null)
  }

  private fun acknowledge(text: String) {
    current = node(text)
    queue.onFocusChanged(current, SystemClock.uptimeMillis())
    shadowOf(Looper.getMainLooper()).idle()
  }

  @Test fun everyCommandInABurstWaitsForItsOwnFocusAndKeepsItsOrder() {
    val commands = List(80) { action(it % 3 == 0) }
    commands.forEach(::submit)
    assertEquals(1, dispatched.size)
    repeat(80) { i ->
      acknowledge("item ${i + 2}")
      assertEquals(minOf(80, i + 2), dispatched.size)
    }
    commands.forEachIndexed { i, command -> assertSame(command, dispatched[i]) }
  }

  @Test fun focusOnTheOriginDoesNotReleaseAnotherMove() {
    submit(action())
    submit(action())
    queue.onFocusChanged(current, SystemClock.uptimeMillis())
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(1, dispatched.size)
    acknowledge("item 2")
    assertEquals(2, dispatched.size)
  }

  @Test fun anOldEventCannotAcknowledgeTheNewAction() {
    submit(action())
    submit(action())
    queue.onFocusChanged(node("item 2"), SystemClock.uptimeMillis() - 1)
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(1, dispatched.size)
  }

  @Test fun rejectionReleasesQueuedCommandsWithoutWaitingForAFocusEvent() {
    submit(action())
    val token = queue.beforeAction(current) // An already pending move cannot be overwritten.
    assertEquals(0L, token)
    submit(action())
    accepted = false
    acknowledge("item 2")
    assertEquals(2, dispatched.size)
    submit(action())
    assertEquals(3, dispatched.size)
  }

  @Test fun anEventArrivingBeforeTheActionReturnsIsKept() {
    val token = queue.beforeAction(current)
    val next = action()
    assertTrue(queue.enqueueIfWaiting(next, null))
    queue.onFocusChanged(node("item 2"), SystemClock.uptimeMillis())
    queue.actionReturned(token, true)
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(listOf(next), dispatched)
  }

  @Test fun aNewCommandCannotOvertakeAnAlreadyPostedDrain() {
    val first = action()
    val second = action(true)
    val third = action()
    submit(first)
    submit(second)
    current = node("item 2")
    queue.onFocusChanged(current, SystemClock.uptimeMillis())
    submit(third)
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(2, dispatched.size)
    assertSame(second, dispatched.last())
    acknowledge("item 3")
    assertSame(third, dispatched.last())
  }

  @Test fun changingWindowsCancelsOldContextCommands() {
    submit(action())
    submit(action())
    queue.onFocusChanged(node("another window", window = 2), SystemClock.uptimeMillis())
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(1, dispatched.size)
    submit(action())
    assertEquals(2, dispatched.size)
  }

  @Test fun removingTheWindowCancelsAPostedDrain() {
    submit(action())
    submit(action())
    queue.onFocusChanged(node("item 2"), SystemClock.uptimeMillis())
    queue.onWindowRemoved(1)
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(1, dispatched.size)
  }

  @Test fun aNewWindowBetweenAcknowledgementAndDrainCancelsOldCommands() {
    submit(action())
    submit(action())
    queue.onFocusChanged(node("item 2"), SystemClock.uptimeMillis())
    queue.onFocusChanged(node("new window", window = 2), SystemClock.uptimeMillis())
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(1, dispatched.size)
    assertFalse(queue.needsFocusEvent())
  }

  @Test fun explorationOrStoppingTheServiceCanCancelPendingWork() {
    submit(action())
    submit(action())
    queue.clear()
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(1, dispatched.size)
    submit(action())
    assertEquals(2, dispatched.size)
  }

  @Test fun aReusedNodeWithChangedTextCanAcknowledgeProgress() {
    submit(action())
    submit(action())
    val changed = AccessibilityNodeInfoCompat.obtain(current)
    changed.text = "replacement item"
    queue.onFocusChanged(changed, SystemClock.uptimeMillis())
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(2, dispatched.size)
  }

  @Test fun elapsedTimeAloneDoesNotGuessProgressOrDropCommands() {
    submit(action())
    submit(action())
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
    assertEquals(1, dispatched.size)
    acknowledge("item 2")
    assertEquals(2, dispatched.size)
  }
}
