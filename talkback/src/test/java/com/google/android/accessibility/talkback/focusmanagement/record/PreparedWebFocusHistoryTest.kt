/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.focusmanagement.record

import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.utils.FormFactorUtils
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreparedWebFocusHistoryTest {
  private lateinit var service: TalkBackService
  private lateinit var history: AccessibilityFocusActionHistory
  private lateinit var pivot: AccessibilityNodeInfoCompat
  private val info = FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build()

  @Before fun setup() {
    service = Robolectric.buildService(TalkBackService::class.java).get()
    FormFactorUtils.initialize(service)
    history = AccessibilityFocusActionHistory(service)
    pivot = node("old")
  }

  private fun node(label: String, window: Int = 1, web: Boolean = true): AccessibilityNodeInfoCompat {
    val raw = AccessibilityNodeInfo.obtain()
    raw.setSource(View(service))
    raw.text = label; raw.className = "android.widget.Button"; raw.packageName = "example.web"
    raw.isVisibleToUser = true; raw.isEnabled = true; raw.isClickable = true; raw.isFocusable = true
    ReflectionHelpers.setField(raw, "mWindowId", window)
    if (web) raw.addAction(AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT)
    return AccessibilityNodeInfoCompat.wrap(raw)
  }

  private fun event(node: AccessibilityNodeInfoCompat, time: Long = SystemClock.uptimeMillis()) =
    AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED).apply {
      eventTime = time
      shadowOf(this).setSourceNode(node.unwrap())
    }

  private fun prepare(info: FocusActionInfo = this.info) =
    history.prepareWebNavigationHistory(pivot, info, SystemClock.uptimeMillis(), null)

  @Test fun sameMillisecondConfirmationRecordsTheActualNode() {
    prepare(); val next = node("next")
    val record = history.matchFocusActionRecordFromEvent(event(next))!!
    assertEquals("next", record.focusedNode.text)
    assertSame(info, record.extraInfo)
    assertNull(history.pendingWebFocusActionInfo)
  }

  @Test fun aFlickQueuedAfterDispatchMutesOnlyTheIntermediateFeedback() {
    var queued=false
    history.setHasQueuedWebNavigationSupplier {queued}
    prepare(); val e=event(node("next"))
    assertFalse(history.reader.getFocusActionInfoFromEvent(e)!!.forceMuteFeedback)
    queued=true
    assertTrue(history.reader.getFocusActionInfoFromEvent(e)!!.forceMuteFeedback)
    assertFalse(history.lastFocusActionRecord!!.extraInfo.forceMuteFeedback)
    queued=false
    assertFalse(history.reader.getFocusActionInfoFromEvent(e)!!.forceMuteFeedback)
  }

  @Test fun theFinalConfirmedMoveRemainsAudible() {
    history.setHasQueuedWebNavigationSupplier {false}
    prepare()
    assertFalse(history.reader.getFocusActionInfoFromEvent(event(node("last")))!!.forceMuteFeedback)
  }

  @Test fun aLaterReaderMutesTheOldEventWhenTheNextMoveHasAlreadyDispatched() {
    prepare(); val next=node("next"); val e=event(next)
    assertFalse(history.reader.getFocusActionInfoFromEvent(e)!!.forceMuteFeedback)
    history.prepareWebNavigationHistory(next,info,SystemClock.uptimeMillis(),null)
    assertTrue(history.reader.getFocusActionInfoFromEvent(e)!!.forceMuteFeedback)
    assertNotNull(history.pendingWebFocusActionInfo)
    assertFalse(history.reader.getFocusActionInfoFromEvent(event(node("last")))!!.forceMuteFeedback)
  }

  @Test fun nativeFocusIsNotMutedByAnUnrelatedWebQueue() {
    val native=node("native",web=false)
    history.onAccessibilityFocusAction(native,info,SystemClock.uptimeMillis(),null)
    history.setHasQueuedWebNavigationSupplier {true}
    assertFalse(history.reader.getFocusActionInfoFromEvent(event(native))!!.forceMuteFeedback)
  }

  @Test fun explicitMuteIsPreservedWhenTheQueueBecomesEmpty() {
    history.setHasQueuedWebNavigationSupplier {false}
    prepare(FocusActionInfo.Builder(info).setForceMuteFeedback(true).build())
    assertTrue(history.reader.getFocusActionInfoFromEvent(event(node("muted")))!!.forceMuteFeedback)
  }

  @Test fun delayedConfirmationStillMatchesForEveryReaderOfThatEvent() {
    prepare(); ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
    val event = event(node("late"))
    val first = history.matchFocusActionRecordFromEvent(event)!!
    assertEquals("late", first.focusedNode.text)
    assertSame(info, history.matchFocusActionRecordFromEvent(event)!!.extraInfo)
  }

  @Test fun staleEventFromAnotherWindowDoesNotCancelTheNewCommand() {
    val time = SystemClock.uptimeMillis(); prepare()
    assertNull(history.matchFocusActionRecordFromEvent(event(node("stale", 2), time - 1)))
    assertSame(info, history.pendingWebFocusActionInfo)
    assertNotNull(history.matchFocusActionRecordFromEvent(event(node("next"))))
  }

  @Test fun unchangedPivotCannotConsumeItsOwnPendingMove() {
    prepare()
    assertNull(history.matchFocusActionRecordFromEvent(event(pivot)))
    assertSame(info, history.pendingWebFocusActionInfo)
  }

  @Test fun rejectedActionRemovesOnlyItsOwnPendingHistory() {
    val token = prepare()
    assertTrue(history.cancelPreparedWebNavigationHistory(token))
    assertNull(history.matchFocusActionRecordFromEvent(event(node("unrelated"))))
  }

  @Test fun lateRejectionCannotEraseAnAlreadyConfirmedMove() {
    val token = prepare(); val confirmed = event(node("next"))
    assertNotNull(history.matchFocusActionRecordFromEvent(confirmed))
    assertFalse(history.cancelPreparedWebNavigationHistory(token))
    assertEquals("next", history.lastFocusActionRecord!!.focusedNode.text)
  }

  @Test fun oldRejectionCannotEraseANewerPreparedCommand() {
    val old = prepare()
    val newer = FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION)
      .setForceMuteFeedback(true).build()
    prepare(newer)
    assertFalse(history.cancelPreparedWebNavigationHistory(old))
    assertSame(newer, history.matchFocusActionRecordFromEvent(event(node("new")))!!.extraInfo)
  }

  @Test fun explicitNativeFocusSupersedesPendingWebNavigation() {
    prepare()
    val native = node("touch", web = false)
    val touched = FocusActionInfo.builder().setSourceAction(FocusActionInfo.TOUCH_EXPLORATION).build()
    history.onAccessibilityFocusAction(native, touched, SystemClock.uptimeMillis(), null)
    assertNull(history.pendingWebFocusActionInfo)
    assertSame(touched, history.matchFocusActionRecordFromEvent(event(native))!!.extraInfo)
  }

  @Test fun changedWindowCannotBeRecordedAsThePreviousWebMove() {
    prepare()
    assertNull(history.matchFocusActionRecordFromEvent(event(node("other window", 2))))
    assertNull(history.pendingWebFocusActionInfo)
  }

  @Test fun recycledIdentityWithANewCollectionPositionIsARealMove() {
    pivot.setCollectionItemInfo(AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(0, 1, 0, 1, false))
    prepare()
    val next = AccessibilityNodeInfoCompat.obtain(pivot)
    next.setCollectionItemInfo(AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(1, 1, 0, 1, false))
    val record = history.matchFocusActionRecordFromEvent(event(next))!!
    assertEquals(1, record.focusedNode.collectionItemInfo!!.rowIndex)
  }

  @Test fun recycledIdentityWithNewTextIsARealMove() {
    prepare()
    val next = AccessibilityNodeInfoCompat.obtain(pivot).apply { text = "changed" }
    assertEquals("changed", history.matchFocusActionRecordFromEvent(event(next))!!.focusedNode.text)
  }

  @Test fun replayOfConfirmedEventCannotConsumeANewerCommand() {
    prepare(); val confirmed = event(node("next"))
    history.matchFocusActionRecordFromEvent(confirmed)
    val newer = FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION)
      .setForceMuteFeedback(true).build()
    prepare(newer)
    assertSame(info, history.matchFocusActionRecordFromEvent(confirmed)!!.extraInfo)
    assertSame(newer, history.pendingWebFocusActionInfo)
  }

  @Test fun clearingHistoryAlsoClearsConfirmedEventAssociations() {
    prepare(); val confirmed = event(node("next"))
    assertNotNull(history.matchFocusActionRecordFromEvent(confirmed))
    history.clear()
    assertNull(history.matchFocusActionRecordFromEvent(confirmed))
  }
}
