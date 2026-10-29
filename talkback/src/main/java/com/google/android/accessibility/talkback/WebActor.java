/*
 * Copyright (C) 2020 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback;

import static com.google.android.accessibility.utils.WebInterfaceUtils.DIRECTION_FORWARD;
import static com.google.android.accessibility.utils.monitor.InputModeTracker.INPUT_MODE_KEYBOARD;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import com.google.android.accessibility.talkback.Feedback.WebAction;
import com.google.android.accessibility.talkback.focusmanagement.NavigationTarget;
import com.google.android.accessibility.talkback.focusmanagement.action.NavigationAction;
import com.google.android.accessibility.talkback.focusmanagement.record.FocusActionInfo;
import com.google.android.accessibility.utils.input.CursorGranularity;
import com.google.android.accessibility.utils.traversal.TraversalStrategyUtils;
import com.google.android.accessibility.utils.PerformActionUtils;
import com.google.android.accessibility.utils.AccessibilityEventListener;
import com.google.android.accessibility.utils.ServiceStateListener;
import com.google.android.accessibility.utils.Performance;
import com.google.android.accessibility.utils.WebInterfaceUtils;
import com.google.android.accessibility.utils.output.FeedbackItem;
import com.google.android.accessibility.utils.output.SpeechController;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.function.BooleanSupplier;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/** WebActor executes WebAction-feedback. */
public class WebActor implements AccessibilityEventListener, ServiceStateListener {
  private BooleanSupplier hasQueuedNavigation = () -> false;
  private @Nullable Runnable pendingHistoryCleanup;
  private int pendingHistoryWindow = -1;

  //////////////////////////////////////////////////////////////////////////////////////////////////
  // UpdateFocusHistory interface

  /** Interface for a update history method, used to update focus history. */
  public interface UpdateFocusHistory {
    void updateHistory(AccessibilityNodeInfoCompat start, FocusActionInfo focusActionInfo);

    /** Arms event-based history before dispatch. Returns cleanup for a rejected action. */
    default @Nullable Runnable prepareNavigationHistory(
        AccessibilityNodeInfoCompat start, FocusActionInfo focusActionInfo) {
      return null;
    }
  }

  private final UpdateFocusHistory focusHistory;

  //////////////////////////////////////////////////////////////////////////////////////////////////

  private static final String LOG_TAG = "WebActor";

  private Pipeline.FeedbackReturner pipeline;
  private final AccessibilityService service;

  public WebActor(AccessibilityService service, UpdateFocusHistory focusHistory) {
    this.service = service;
    this.focusHistory = focusHistory;
  }

  public void setPipeline(Pipeline.FeedbackReturner pipeline) {
    this.pipeline = pipeline;
  }

  public void setHasQueuedNavigationSupplier(BooleanSupplier hasQueuedNavigation) {
    this.hasQueuedNavigation = hasQueuedNavigation;
  }

  public void cancelPreparedNavigationHistory() {
    Runnable cleanup = pendingHistoryCleanup;
    pendingHistoryCleanup = null;
    pendingHistoryWindow = -1;
    if (cleanup != null) {
      cleanup.run();
    }
  }

  @Override
  public int getEventTypes() {
    return AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START
        | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event, Performance.EventId eventId) {
    if (event.getEventType() == AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START
        || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            && event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            && event.getWindowId() == pendingHistoryWindow
            && (event.getWindowChanges() & AccessibilityEvent.WINDOWS_CHANGE_REMOVED) != 0)) {
      cancelPreparedNavigationHistory();
    }
  }

  @Override
  public void onServiceStateChanged(int state) {
    if (state != SERVICE_STATE_ACTIVE) {
      cancelPreparedNavigationHistory();
    }
  }

  ///////////////////////////////////////////////////////////////////////////////////////
  // Methods

  /**
   * Perform web navigation action and update focus history with source action, {@code
   * FocusActionInfo.LOGICAL_NAVIGATION}
   */
  public boolean performAction(@NonNull WebAction webAction, Performance.EventId eventId) {
    cancelPreparedNavigationHistory();
    boolean result =
        PerformActionUtils.performAction(
            webAction.target(), webAction.nodeAction(), webAction.nodeActionArgs(), eventId);
    if (webAction.updateFocusHistory() && (focusHistory != null)) {
      // The source action of FocusActionInfo only has FocusActionInfo.LOGICAL_NAVIGATION since web
      // navigation only has navigation action.
      focusHistory.updateHistory(
          webAction.target(),
          FocusActionInfo.builder()
              .setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION)
              .setNavigationAction(webAction.navigationAction())
              .setForceMuteFeedback(hasQueuedNavigation.getAsBoolean())
              .build());
    }
    return result;
  }

  public boolean navigateToHtmlElement(
      AccessibilityNodeInfoCompat start,
      NavigationAction navigationAction,
      Performance.EventId eventId) {
    cancelPreparedNavigationHistory();

    String htmlElementType = NavigationTarget.targetTypeToHtmlElement(navigationAction.targetType);
    if (htmlElementType == null) {
      LogUtils.w(
          LOG_TAG,
          "Cannot navigate to HTML target: invalid targetType=%d.",
          navigationAction.targetType);
      return false;
    }

    Bundle args = new Bundle();
    args.putString(
        AccessibilityNodeInfoCompat.ACTION_ARGUMENT_HTML_ELEMENT_STRING, htmlElementType);

    if (!WebInterfaceUtils.supportsWebActions(start)) {
      LogUtils.w(LOG_TAG, "Cannot navigate to HTML target: current pivot is not a web element.");
      if (navigationAction.inputMode == INPUT_MODE_KEYBOARD) {
        speakTTSText(service.getString(R.string.keycombo_announce_shortcut_not_supported), eventId);
      }
      return false;
    }

    int webNavigationDirection =
        WebInterfaceUtils.searchDirectionToWebNavigationDirection(
            /* context= */ service, navigationAction.searchDirection);
    if (webNavigationDirection == 0) {
      LogUtils.w(LOG_TAG, "Cannot navigate to HTML target: invalid direction.");
      return false;
    }
    int action =
        (webNavigationDirection == DIRECTION_FORWARD)
            ? AccessibilityNodeInfoCompat.ACTION_NEXT_HTML_ELEMENT
            : AccessibilityNodeInfoCompat.ACTION_PREVIOUS_HTML_ELEMENT;

    FocusActionInfo focusInfo =
        FocusActionInfo.builder()
            .setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION)
            .setNavigationAction(navigationAction)
            .setForceMuteFeedback(hasQueuedNavigation.getAsBoolean())
            .build();
    // Providers can report focus before the action returns. Arm first, then consume the actual
    // event, rather than synchronously asking the provider for focus and refreshing it again.
    Runnable rejected =
        focusHistory != null && isDefaultLogicalNavigation(navigationAction)
            ? focusHistory.prepareNavigationHistory(start, focusInfo) : null;
    pendingHistoryCleanup = rejected;
    pendingHistoryWindow = rejected == null ? -1 : start.getWindowId();
    boolean accepted = false;
    try {
      accepted = PerformActionUtils.performAction(start, action, args, eventId);
    } finally {
      if (!accepted && rejected != null) {
        rejected.run();
        if (pendingHistoryCleanup == rejected) {
          pendingHistoryCleanup = null;
          pendingHistoryWindow = -1;
        }
      }
    }
    if (accepted) {
      if (focusHistory != null && rejected == null) {
        focusHistory.updateHistory(
            start, focusInfo);
      }
      return true;
    } else {
      int resId =
          webNavigationDirection == WebInterfaceUtils.DIRECTION_FORWARD
              ? R.string.end_of_page
              : R.string.start_of_page;
      String ttsText =
          service.getString(
              resId,
              NavigationTarget.htmlTargetToDisplayName(
                  /* context= */ service, navigationAction.targetType));

      speakTTSText(ttsText, eventId);
      return false;
    }
  }

  private static boolean isDefaultLogicalNavigation(NavigationAction action) {
    return action.actionType == NavigationAction.DIRECTIONAL_NAVIGATION
        && action.targetType == NavigationTarget.TARGET_DEFAULT
        && (action.originalNavigationGranularity == null
            || action.originalNavigationGranularity == CursorGranularity.DEFAULT)
        && TraversalStrategyUtils.isLogicalDirection(action.searchDirection);
  }

  private void speakTTSText(CharSequence ttsText, Performance.EventId eventId) {
    SpeechController.SpeakOptions speakOptions =
        SpeechController.SpeakOptions.create()
            .setQueueMode(SpeechController.QUEUE_MODE_INTERRUPT)
            .setFlags(FeedbackItem.FLAG_FORCE_FEEDBACK);
    pipeline.returnFeedback(eventId, Feedback.speech(ttsText, speakOptions));
  }
}
