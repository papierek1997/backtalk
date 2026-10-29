/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.accessibility.utils.AccessibilityEventUtils
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FocusEventSourceQueriesTest {
  private fun event(type: Int): AccessibilityEvent = AccessibilityEvent.obtain(type).apply {
    Shadow.extract<PrefetchingAccessibilityRecordShadow>(this).setSourceNode(
      AccessibilityNodeInfo.obtain().apply {text="Grouped item label"})
  }

  private fun strategies(event: AccessibilityEvent) =
    Shadow.extract<PrefetchingAccessibilityRecordShadow>(event).strategies

  @Test fun focusAndExplorationRetrieveContextWithoutSpeculativeDescendants() {
    for(type in listOf(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)) {
      val e=event(type)
      assertEquals("Grouped item label",AccessibilityEventUtils.getSource(e)!!.text)
      assertEquals(listOf(3),strategies(e))
    }
  }

  @Test fun scrollAndTextEventsKeepTheirExistingFetchPolicy() {
    for(type in listOf(AccessibilityEvent.TYPE_VIEW_SCROLLED,AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
      val e=event(type)
      assertEquals("Grouped item label",AccessibilityEventUtils.getSource(e)!!.text)
      assertEquals(listOf<Int?>(null),strategies(e))
    }
  }

  @Test @Config(sdk=[32]) fun olderAndroidKeepsTheSupportedSourceApi() {
    val e=event(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
    assertEquals("Grouped item label",AccessibilityEventUtils.getSource(e)!!.text)
    assertEquals(listOf<Int?>(null),strategies(e))
  }

  @Test fun aMissingSourceOrEventRemainsHarmless() {
    assertNull(AccessibilityEventUtils.getSource(null))
    val e=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
    assertNull(AccessibilityEventUtils.sourceCompat(e))
  }
}
