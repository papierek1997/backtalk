/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.actor

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.Filter
import java.util.concurrent.CancellationException
import java.util.function.BooleanSupplier
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Mask traversal keeps nested matches, order and cycle protection while stale work can stop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageCaptionTraversalTest {
  private class Tree(val name: String) : AccessibilityNodeInfoCompat(
    AccessibilityNodeInfo(View(RuntimeEnvironment.getApplication()))
  ) {
    val children = mutableListOf<Tree?>()
    val requests = mutableListOf<Pair<Int, Int?>>()
    override fun getChildCount(): Int = children.size
    override fun getChild(index: Int): AccessibilityNodeInfoCompat? {
      requests += index to null
      return children[index]
    }
    override fun getChild(index: Int, strategy: Int): AccessibilityNodeInfoCompat? {
      requests += index to strategy
      return children[index]
    }
  }

  private fun collect(root: Tree, metadataOnly: Boolean = true,
    stale: BooleanSupplier = BooleanSupplier { false },
    filter: Filter<AccessibilityNodeInfoCompat> = Filter.node { true }
  ): List<AccessibilityNodeInfoCompat> = mutableListOf<AccessibilityNodeInfoCompat>().apply {
    ImageCaptioner.collectCaptionOverlaps(root, filter, hashSetOf(), this, stale, metadataOnly)
  }

  @Test fun allNestedMatchesKeepTheOriginalTraversalOrder() {
    val root = Tree("root")
    val first = Tree("first")
    val nested = Tree("nested")
    val second = Tree("second")
    root.children += listOf(first, null, second)
    first.children += nested
    val filter = Filter.node { true }
    val original = AccessibilityNodeInfoUtils.getMatchingDescendantsOrRoot(root, filter)
    root.requests.clear(); first.requests.clear()
    assertEquals(original, collect(root, filter = filter))
    assertEquals(listOf(root, first, nested, second), collect(root))
    assertTrue(root.requests.all { it.second == 3 })
    assertTrue(first.requests.all { it.second == 3 })
  }

  @Test fun aCycleDoesNotRepeatMatchesOrBlockCancellation() {
    val root = Tree("root")
    val child = Tree("child")
    root.children += child
    child.children += root
    assertEquals(listOf(root, child), collect(root))
  }

  @Test fun cancellationStopsBeforeAnotherRemoteChildRequest() {
    val root = Tree("root")
    val first = Tree("first")
    root.children += listOf(first, Tree("never requested"))
    var stale = false
    val filter = Filter.node {
      if (it == first) stale = true
      true
    }
    assertThrows(CancellationException::class.java) {
      collect(root, stale = BooleanSupplier { stale }, filter = filter)
    }
    assertEquals(listOf(0 to 3), root.requests)
  }

  @Test fun explicitRecognitionKeepsTheExistingPrefetchPolicy() {
    val root = Tree("root").apply { children += Tree("child") }
    assertEquals(2, collect(root, metadataOnly = false).size)
    assertEquals(listOf(0 to null), root.requests)
  }

  @Test @Config(sdk = [32]) fun automaticRecognitionUsesTheOlderChildApiWhereRequired() {
    val root = Tree("root").apply { children += Tree("child") }
    assertEquals(2, collect(root).size)
    assertEquals(listOf(0 to null), root.requests)
  }
}
