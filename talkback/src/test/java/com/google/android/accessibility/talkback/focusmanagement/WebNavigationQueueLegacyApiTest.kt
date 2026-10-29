/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.focusmanagement

import android.view.accessibility.AccessibilityEvent
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.compositor.Compositor
import com.google.android.accessibility.utils.FormFactorUtils
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class WebNavigationQueueLegacyApiTest {
  @Test fun legacyWindowChangesDoNotCallNewerFrameworkMethods() {
    val service = Robolectric.buildService(TalkBackService::class.java).get()
    FormFactorUtils.initialize(service)
    val processor = FocusProcessorForLogicalNavigation(service, null, null, null, null,
      Compositor.TextComposer { _, _, _ -> null }, null)
    processor.onNavigationAccessibilityEvent(
      AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
  }
}
