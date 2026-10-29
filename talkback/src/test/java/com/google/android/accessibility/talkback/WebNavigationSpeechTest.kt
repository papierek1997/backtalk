/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.focusmanagement.NavigationTarget
import com.google.android.accessibility.talkback.focusmanagement.action.NavigationAction
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.traversal.TraversalStrategy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebNavigationSpeechTest {
  @Test fun onlyIntermediateQueuedMovesMuteSpeechAndTheLastMoveSpeaksNormally() {
    val service = Robolectric.buildService(TalkBackService::class.java).get()
    FormFactorUtils.initialize(service)
    val history = ArrayList<FocusActionInfo>()
    val actor = WebActor(service) { _, info -> history.add(info) }
    var waiting = false
    actor.setHasQueuedNavigationSupplier { waiting }
    val pivot = object : AccessibilityNodeInfoCompat(AccessibilityNodeInfo.obtain()) {
      override fun performAction(action: Int, arguments: Bundle?): Boolean = true
    }
    pivot.addAction(AccessibilityNodeInfoCompat.ACTION_NEXT_HTML_ELEMENT)
    pivot.addAction(AccessibilityNodeInfoCompat.ACTION_PREVIOUS_HTML_ELEMENT)
    val action = NavigationAction.Builder().setAction(NavigationAction.DIRECTIONAL_NAVIGATION)
      .setTarget(NavigationTarget.TARGET_DEFAULT).setDirection(TraversalStrategy.SEARCH_FOCUS_FORWARD).build()
    assertTrue(actor.navigateToHtmlElement(pivot, action, null))
    waiting = true
    assertTrue(actor.navigateToHtmlElement(pivot, action, null))
    waiting = false
    assertTrue(actor.navigateToHtmlElement(pivot, action, null))
    assertEquals(3, history.size)
    assertFalse(history[0].forceMuteFeedback)
    assertTrue(history[1].forceMuteFeedback)
    assertFalse(history[2].forceMuteFeedback)
    history.forEach { assertSame(action, it.navigationAction) }
  }
}
