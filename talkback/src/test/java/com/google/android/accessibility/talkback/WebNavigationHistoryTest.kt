/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback

import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.focusmanagement.NavigationTarget
import com.google.android.accessibility.talkback.focusmanagement.action.NavigationAction
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.input.CursorGranularity
import com.google.android.accessibility.utils.traversal.TraversalStrategy
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebNavigationHistoryTest {
  private lateinit var actor: WebActor
  private lateinit var pivot: AccessibilityNodeInfoCompat
  private val order = ArrayList<String>()
  private var accepted = true
  private var fail = false

  @Before fun setup() {
    val service = Robolectric.buildService(TalkBackService::class.java).get()
    FormFactorUtils.initialize(service)
    actor = WebActor(service, object : WebActor.UpdateFocusHistory {
      override fun updateHistory(start: AccessibilityNodeInfoCompat, info: FocusActionInfo) {order.add("legacy")}
      override fun prepareNavigationHistory(start: AccessibilityNodeInfoCompat, info: FocusActionInfo): Runnable {
        order.add("prepare")
        return Runnable {order.add("cancel")}
      }
    })
    actor.setPipeline(Pipeline.FeedbackReturner {true})
    pivot = object : AccessibilityNodeInfoCompat(AccessibilityNodeInfo.obtain()) {
      override fun performAction(action: Int, arguments: Bundle?): Boolean {
        order.add("dispatch")
        if (fail) throw IllegalStateException("provider failed")
        return accepted
      }
    }
    pivot.addAction(AccessibilityNodeInfoCompat.ACTION_NEXT_HTML_ELEMENT)
    pivot.addAction(AccessibilityNodeInfoCompat.ACTION_PREVIOUS_HTML_ELEMENT)
    ReflectionHelpers.setField(pivot.unwrap(), "mWindowId", 1)
  }

  private fun action(target: Int = NavigationTarget.TARGET_DEFAULT, granularity: CursorGranularity? = null) =
    NavigationAction.Builder().setAction(NavigationAction.DIRECTIONAL_NAVIGATION)
      .setTarget(target).setDirection(TraversalStrategy.SEARCH_FOCUS_FORWARD)
      .setOriginalNavigationGranularity(granularity).build()

  @Test fun acceptedMoveArmsHistoryBeforeProviderAndDoesNotPollForFocus() {
    assertTrue(actor.navigateToHtmlElement(pivot, action(), null))
    assertEquals(listOf("prepare", "dispatch"), order)
  }

  @Test fun rejectedMoveCancelsItsPreparedHistory() {
    accepted = false
    assertFalse(actor.navigateToHtmlElement(pivot, action(), null))
    assertEquals(listOf("prepare", "dispatch", "cancel"), order)
  }

  @Test fun providerExceptionAlsoCancelsPreparedHistory() {
    fail = true
    assertThrows(IllegalStateException::class.java) {actor.navigateToHtmlElement(pivot, action(), null)}
    assertEquals(listOf("prepare", "dispatch", "cancel"), order)
  }

  @Test fun headingsKeepTheirExistingHistoryPath() {
    assertTrue(actor.navigateToHtmlElement(pivot, action(NavigationTarget.TARGET_HEADING), null))
    assertEquals(listOf("dispatch", "legacy"), order)
  }

  @Test fun leavingMicroGranularityKeepsItsExistingHistoryPath() {
    assertTrue(actor.navigateToHtmlElement(pivot, action(granularity = CursorGranularity.WORD), null))
    assertEquals(listOf("dispatch", "legacy"), order)
  }

  @Test fun exploringSupersedesPreparedNavigation() {
    actor.navigateToHtmlElement(pivot, action(), null)
    actor.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START), null)
    assertEquals(listOf("prepare", "dispatch", "cancel"), order)
    actor.cancelPreparedNavigationHistory()
    assertEquals(1, order.count {it == "cancel"})
  }

  @Test fun removingAnotherWindowDoesNotCancelThisWindow() {
    actor.navigateToHtmlElement(pivot, action(), null)
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED)
    ReflectionHelpers.setField(event, "mWindowChangeTypes", AccessibilityEvent.WINDOWS_CHANGE_REMOVED)
    shadowOf(event).setWindowId(2)
    actor.onAccessibilityEvent(event, null)
    assertEquals(listOf("prepare", "dispatch"), order)
    shadowOf(event).setWindowId(1)
    actor.onAccessibilityEvent(event, null)
    assertEquals(listOf("prepare", "dispatch", "cancel"), order)
  }

  @Test fun stoppingTheServiceCancelsPreparedHistory() {
    actor.navigateToHtmlElement(pivot, action(), null)
    actor.onServiceStateChanged(0)
    assertEquals(listOf("prepare", "dispatch", "cancel"), order)
  }
}
