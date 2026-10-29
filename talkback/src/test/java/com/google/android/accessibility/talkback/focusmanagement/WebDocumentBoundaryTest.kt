/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.focusmanagement

import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.compositor.Compositor
import com.google.android.accessibility.talkback.focusmanagement.action.NavigationAction
import com.google.android.accessibility.utils.Filter
import com.google.android.accessibility.utils.FormFactorUtils
import com.google.android.accessibility.utils.Performance
import com.google.android.accessibility.utils.traversal.TraversalStrategy
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebDocumentBoundaryTest {
  private lateinit var processor: FocusProcessorForLogicalNavigation
  private val attempts = ArrayList<AccessibilityNodeInfoCompat>()
  private val empty = HashSet<AccessibilityNodeInfoCompat>()
  private val nativeFilter = Filter.node {it.className == "android.widget.Button"}
  private var order = listOf<Node>()
  private var nextOverride: ((AccessibilityNodeInfoCompat) -> AccessibilityNodeInfoCompat?)? = null
  private var nextId = 0

  private inner class Node(web: Boolean = false, label: String = "") :
    AccessibilityNodeInfoCompat(AccessibilityNodeInfo.obtain()) {
    private val identity = ++nextId
    var parentNode: Node? = null
    init {
      className = if(web) "android.webkit.WebView" else "android.widget.Button"
      text = label; packageName = "example.web"; isVisibleToUser = true
      if(web) {addAction(ACTION_NEXT_HTML_ELEMENT); addAction(ACTION_PREVIOUS_HTML_ELEMENT)}
    }
    override fun getParent(): AccessibilityNodeInfoCompat? = parentNode
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = identity
  }

  private val traversal = object : TraversalStrategy {
    override fun findFocus(start: AccessibilityNodeInfoCompat, direction: Int): AccessibilityNodeInfoCompat? {
      nextOverride?.let {return it(start)}
      val index = order.indexOf(start)
      return order.getOrNull(index + if(direction == TraversalStrategy.SEARCH_FOCUS_BACKWARD) -1 else 1)
    }
    override fun focusFirst(root: AccessibilityNodeInfoCompat, direction: Int): AccessibilityNodeInfoCompat? =
      if(direction == TraversalStrategy.SEARCH_FOCUS_BACKWARD) order.lastOrNull() else order.firstOrNull()
    override fun getSpeakingNodesCache(): MutableMap<AccessibilityNodeInfoCompat, Boolean> = HashMap()
  }

  @Before fun setup() {
    val service = Robolectric.buildService(TalkBackService::class.java).get()
    FormFactorUtils.initialize(service)
    processor = FocusProcessorForLogicalNavigation(service, null, null, null, null,
      Compositor.TextComposer {_,_,_ -> null}, null)
    processor.setPipeline(Pipeline.FeedbackReturner {feedback ->
      val node = feedback.failovers()[0].webAction()!!.target()
      attempts.add(node)
      !empty.contains(node)
    })
  }

  private fun action(backward: Boolean, target: Int) = NavigationAction.Builder()
    .setAction(NavigationAction.DIRECTIONAL_NAVIGATION).setTarget(target)
    .setDirection(if(backward) TraversalStrategy.SEARCH_FOCUS_BACKWARD else TraversalStrategy.SEARCH_FOCUS_FORWARD).build()

  private fun find(start: Node, backward: Boolean = false, target: Int = NavigationTarget.TARGET_DEFAULT,
    method: String = "findTargetOutsideWebElement"): Any =
    ReflectionHelpers.callInstanceMethod(processor, method,
      from(AccessibilityNodeInfoCompat::class.java, start), from(NavigationAction::class.java, action(backward,target)),
      from(Filter::class.java, nativeFilter), from(TraversalStrategy::class.java, traversal),
      from(Performance.EventId::class.java, Performance.EVENT_ID_UNTRACKED))

  private fun isWeb(result: Any) = ReflectionHelpers.callInstanceMethod<Boolean>(result,"isWebElement")
  private fun isEmpty(result: Any) = ReflectionHelpers.callInstanceMethod<Boolean>(result,"isEmpty")
  private fun node(result: Any) = ReflectionHelpers.callInstanceMethod<AccessibilityNodeInfoCompat?>(result,"getNode")

  @Test fun backwardEntersThePreviousDocumentInsteadOfSkippingItsContents() {
    val head = Node(); val previous = Node(true); val current = Node(true)
    order = listOf(head,previous,current)
    assertTrue(isWeb(find(current,backward=true)))
    assertEquals(listOf(previous),attempts)
  }

  @Test fun forwardEntersTheNextDocumentInsteadOfSkippingItsContents() {
    val current = Node(true); val next = Node(true); val tail = Node()
    order = listOf(current,next,tail)
    assertTrue(isWeb(find(current)))
    assertEquals(listOf(next),attempts)
  }

  @Test fun emptyDocumentsAreCrossedOnceUntilARealDocumentAcceptsTheMove() {
    val current = Node(true); val a = Node(true); val b = Node(true); val next = Node(true)
    order = listOf(current,a,b,next,Node()); empty.addAll(listOf(a,b))
    assertTrue(isWeb(find(current)))
    assertEquals(listOf(a,b,next),attempts)
  }

  @Test fun emptyDocumentsFallBackToTheFollowingNativeControl() {
    val current = Node(true); val blank = Node(true); val tail = Node()
    order = listOf(current,blank,tail); empty.add(blank)
    assertSame(tail,node(find(current)))
    assertEquals(listOf(blank),attempts)
  }

  @Test fun aLoopInProviderOrderCannotRepeatTheSameDocumentForever() {
    val current = Node(true); val blank = Node(true); empty.add(blank)
    nextOverride = {if(it === current) blank else current}
    assertTrue(isEmpty(find(current)))
    assertEquals(listOf(blank),attempts)
  }

  @Test fun nativeControlsBetweenDocumentsRemainStops() {
    val current = Node(true); val native = Node(); order = listOf(current,native,Node(true))
    assertSame(native,node(find(current)))
    assertTrue(attempts.isEmpty())
  }

  @Test fun macroNavigationKeepsItsExistingFilter() {
    val current = Node(true); val tail = Node(); order = listOf(current,Node(true),tail)
    assertSame(tail,node(find(current,target=NavigationTarget.TARGET_HEADING)))
    assertTrue(attempts.isEmpty())
  }

  @Test fun enteringAnUnnamedRootFromNativeNavigationDoesNotConsumeAnEmptyStop() {
    val doc = Node(true); order = listOf(doc,Node())
    assertTrue(isWeb(find(doc,method="findTargetFromMiddlePivot")))
    assertEquals(listOf(doc),attempts)
  }

  @Test fun namedDocumentRootsKeepTheirReadableLabel() {
    val doc = Node(true,"Article title"); order = listOf(doc,Node())
    assertSame(doc,node(find(doc,method="findTargetFromMiddlePivot")))
    assertTrue(attempts.isEmpty())
  }

  @Test fun backwardLeavingANamedDocumentIncludesItsTitle() {
    val doc = Node(true,"Article title"); val leaf = Node().apply {parentNode=doc;addAction(AccessibilityNodeInfoCompat.ACTION_PREVIOUS_HTML_ELEMENT)}
    order = listOf(Node(),doc)
    assertSame(doc,node(find(leaf,backward=true)))
    assertTrue(attempts.isEmpty())
  }

  @Test fun longRunsOfEmptyDocumentsUseIteration() {
    val current = Node(true); val blanks = List(1000){Node(true)}; val tail = Node()
    order = listOf(current)+blanks+tail; empty.addAll(blanks)
    assertSame(tail,node(find(current)))
    assertEquals(1000,attempts.size)
  }
}
