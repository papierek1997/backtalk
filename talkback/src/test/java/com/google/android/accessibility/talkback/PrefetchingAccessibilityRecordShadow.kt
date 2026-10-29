/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityRecord
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityRecord

/** Robolectric 4.17 stores the test source only in its no-argument getSource() shadow. */
@Implements(AccessibilityRecord::class)
class PrefetchingAccessibilityRecordShadow : ShadowAccessibilityRecord() {
  @JvmField val strategies = mutableListOf<Int?>()

  @Implementation
  protected override fun getSource(): AccessibilityNodeInfo? {
    strategies.add(null)
    return super.getSource()
  }

  @Implementation(minSdk = 33)
  protected fun getSource(prefetchingStrategy: Int): AccessibilityNodeInfo? {
    strategies.add(prefetchingStrategy)
    return super.getSource()
  }
}
