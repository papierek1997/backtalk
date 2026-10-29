package com.google.android.accessibility.talkback.focusmanagement.record

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.utils.FocusFinder
import com.google.android.accessibility.utils.FormFactorUtils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebFocusHistoryTest {
  private var nextId=0
  private lateinit var finder: FocusFinder
  private inner class Node(
    label: String,
    kind: String="android.widget.Button",
    html: Boolean=false,
    stableId: String?=label,
  ) : AccessibilityNodeInfoCompat(AccessibilityNodeInfo.obtain()) {
    val id=++nextId
    val children=ArrayList<Node>()
    var parentNode: Node?=null
    var live=true
    var childReads=0
    init {
      className=kind;text=label;packageName="example.web";uniqueId=stableId
      isVisibleToUser=true;isEnabled=true;isFocusable=kind.endsWith("Button");isClickable=isFocusable
      setBoundsInScreen(Rect(0,0,100,100))
      if(html){addAction(ACTION_NEXT_HTML_ELEMENT);addAction(ACTION_PREVIOUS_HTML_ELEMENT)}
    }
    fun add(child: Node){children.add(child);child.parentNode=this}
    override fun getParent(): AccessibilityNodeInfoCompat?=parentNode
    override fun getChildCount(): Int=children.size
    override fun getChild(index: Int): AccessibilityNodeInfoCompat? {childReads++;return children.getOrNull(index)}
    override fun refresh(): Boolean=live
    override fun equals(other: Any?): Boolean=this===other
    override fun hashCode(): Int=id
  }
  private lateinit var window: Node
  private lateinit var document: Node
  private lateinit var leaf: Node
  @Before fun setup() {
    val service=Robolectric.buildService(TalkBackService::class.java).get()
    FormFactorUtils.initialize(service);finder=FocusFinder(service)
    window=Node("window","android.widget.FrameLayout")
    document=Node("document","android.webkit.WebView",true)
    leaf=Node("item",html=true)
    window.add(document);document.add(leaf)
  }
  private fun record(node: Node=leaf)=FocusActionRecord(node,
    FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build(),1)
  private fun restore(record: FocusActionRecord)=FocusActionRecord.getFocusableNodeFromFocusRecord(window,finder,record)

  @Test fun stableWebHistoryKeepsEveryAncestorWithoutReadingChildrenOrSiblings() {
    val path=record().nodePathDescription
    assertTrue(path.usesWebIdentity())
    assertTrue(path.containsNodeByHashAndIdentity(leaf))
    assertTrue(path.containsNodeByHashAndIdentity(document))
    assertTrue(path.containsNodeByHashAndIdentity(window))
    assertEquals(0,window.childReads+document.childReads+leaf.childReads)
  }
  @Test fun validWebFocusRestoresTheExactNodeWithoutTreeSearch() {
    val saved=record()
    assertSame(leaf,restore(saved))
    assertEquals(0,window.childReads+document.childReads)
  }
  @Test fun removedWebDocumentDoesNotSearchOtherRetainedDocuments() {
    val saved=record();leaf.live=false;document.live=false
    val other=Node("other","android.webkit.WebView",true);other.add(Node("item",html=true));window.add(other)
    assertNull(restore(saved))
    assertEquals(0,window.childReads+document.childReads+other.childReads)
  }
  @Test fun aReplacedWebNodeUsesItsStableIdOnlyInsideItsOriginalDocument() {
    val saved=record();leaf.live=false
    val replacement=Node("item",html=true)
    document.children.clear();document.add(replacement)
    assertSame(replacement,restore(saved))
    assertEquals(0,window.childReads)
    assertTrue(document.childReads>0)
  }
  @Test fun changingTheDocumentsStableIdCannotRestoreIntoANewDocument() {
    val saved=record();leaf.live=false;document.uniqueId="new document"
    assertNull(restore(saved))
    assertEquals(0,window.childReads+document.childReads)
  }
  @Test fun copiedWebRecordsKeepTheOriginalDocumentBoundary() {
    val saved=FocusActionRecord.copy(record())!!;leaf.live=false;document.live=false
    assertNull(restore(saved))
    assertTrue(saved.nodePathDescription.usesWebIdentity())
    assertEquals(0,window.childReads+document.childReads)
  }
  @Test fun webProvidersWithoutStableIdsKeepStructuralHistory() {
    leaf.uniqueId=null
    val saved=record()
    assertFalse(saved.nodePathDescription.usesWebIdentity())
    assertTrue(document.childReads>0)
  }
  @Test fun htmlProvidersWithoutAWebViewBoundaryKeepStructuralHistory() {
    document.className="android.widget.FrameLayout"
    val saved=record()
    assertFalse(saved.nodePathDescription.usesWebIdentity())
    assertTrue(document.childReads>0)
  }
  @Test fun nativeRecordsKeepTheirWindowWideStableIdRecovery() {
    val native=Node("native");window.children.clear();window.add(native)
    val saved=record(native);native.live=false
    val replacement=Node("native");window.children.clear();window.add(replacement)
    assertFalse(saved.nodePathDescription.usesWebIdentity())
    assertSame(replacement,restore(saved))
  }
}
