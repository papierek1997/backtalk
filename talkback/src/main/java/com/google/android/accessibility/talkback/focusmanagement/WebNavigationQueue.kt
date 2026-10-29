/* Copyright 2026 Backtalk contributors. Licensed under the Apache License, Version 2.0. */
package com.google.android.accessibility.talkback.focusmanagement

import android.os.Handler
import android.os.SystemClock
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.focusmanagement.action.NavigationAction
import com.google.android.accessibility.utils.Performance.EventId
import java.util.ArrayDeque

/**
 * Serializes web cursor moves on the service thread. A successful HTML action means accepted, not
 * that the next gesture can already use its focus. Keep every command until that focus arrives.
 */
class WebNavigationQueue(
  private val handler: Handler,
  private val dispatch: (NavigationAction, EventId?) -> Boolean,
) {
  private data class Command(val action: NavigationAction, val eventId: EventId?)
  private data class Pending(
    val token: Long,
    val node: AccessibilityNodeInfoCompat,
    val text: String?,
    val description: String?,
    val row: Int?,
    val column: Int?,
    val time: Long,
  )

  private val commands = ArrayDeque<Command>()
  private var pending: Pending? = null
  private var nextToken = 0L
  private var drainPosted = false
  private var dispatching = false
  private var windowId = -1
  private val drain = Runnable {
    drainPosted = false
    if (pending == null && commands.isNotEmpty()) {
      val command = commands.removeFirst()
      dispatching = true
      try {
        dispatch(command.action, command.eventId)
      } finally {
        dispatching = false
        scheduleDrain()
      }
    }
  }

  fun hasQueuedCommands(): Boolean = commands.isNotEmpty()

  fun needsFocusEvent(): Boolean = pending != null || commands.isNotEmpty()

  /** New commands also join an already posted drain, so they cannot overtake older commands. */
  fun enqueueIfWaiting(action: NavigationAction, eventId: EventId?): Boolean {
    if (pending == null && commands.isEmpty() && !drainPosted && !dispatching) return false
    commands.addLast(Command(action, eventId))
    scheduleDrain()
    return true
  }

  /** Arm before performing the action: providers may post the focus event while it is running. */
  fun beforeAction(node: AccessibilityNodeInfoCompat): Long {
    if (pending != null) return 0L
    val item = node.collectionItemInfo
    val token = ++nextToken
    windowId = node.windowId
    pending = Pending(token, AccessibilityNodeInfoCompat.obtain(node), node.text?.toString(),
      node.contentDescription?.toString(), item?.rowIndex, item?.columnIndex, SystemClock.uptimeMillis())
    return token
  }

  fun actionReturned(token: Long, accepted: Boolean) {
    if (!accepted && pending?.token == token) {
      pending = null
      scheduleDrain()
    }
  }

  fun onFocusChanged(node: AccessibilityNodeInfoCompat, eventTime: Long) {
    // A new window may gain focus after an acknowledgement but before the posted drain runs.
    if (needsFocusEvent() && node.windowId != windowId) {
      clear()
      return
    }
    val waiting = pending ?: return
    if (eventTime < waiting.time) return
    if (node.windowId != windowId || node.packageName != waiting.node.packageName) {
      clear()
      return
    }
    val item = node.collectionItemInfo
    if (node == waiting.node && node.text?.toString() == waiting.text &&
      node.contentDescription?.toString() == waiting.description &&
      item?.rowIndex == waiting.row && item?.columnIndex == waiting.column) return
    pending = null
    scheduleDrain()
  }

  fun onWindowRemoved(removedWindowId: Int) {
    if (removedWindowId == windowId) clear()
  }

  /** Explicit context changes, exploration and stopping the service supersede queued navigation. */
  fun clear() {
    pending = null
    commands.clear()
    handler.removeCallbacks(drain)
    drainPosted = false
    windowId = -1
  }

  private fun scheduleDrain() {
    if (pending == null && commands.isNotEmpty() && !drainPosted && !dispatching) {
      drainPosted = true
      // All focus listeners must finish updating history before the next command fetches its pivot.
      handler.post(drain)
    }
  }
}
