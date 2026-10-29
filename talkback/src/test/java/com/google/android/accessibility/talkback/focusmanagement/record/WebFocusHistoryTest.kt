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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

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
    val children=ArrayList<Node>()
    var parentNode: Node?=null
    var live=true
      set(value) {field=value;shadowOf(unwrap()).setRefreshReturnValue(value)}
    var childReads=0
    init {
      className=kind;text=label;packageName="example.web";uniqueId=stableId
      isVisibleToUser=true;isEnabled=true;isFocusable=kind.endsWith("Button");isClickable=isFocusable
      isImportantForAccessibility=true
      shadowOf(unwrap()).setRefreshReturnValue(true)
      setBoundsInScreen(Rect(0,0,100,100))
      if(html){addAction(ACTION_NEXT_HTML_ELEMENT);addAction(ACTION_PREVIOUS_HTML_ELEMENT)}
    }
    fun add(child: Node){children.add(child);child.parentNode=this;shadowOf(unwrap()).addChild(child.unwrap())}
    fun clearChildren() {
      children.clear()
      ReflectionHelpers.setField(shadowOf(unwrap()),"children",ArrayList<AccessibilityNodeInfo>())
    }
    override fun getParent(): AccessibilityNodeInfoCompat?=parentNode
    override fun getChildCount(): Int=children.size
    override fun getChild(index: Int): AccessibilityNodeInfoCompat? {childReads++;return children.getOrNull(index)}
    override fun refresh(): Boolean=live
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
  private fun record(node: Node=leaf): FocusActionRecord {
    val saved=FocusActionRecord(node,
      FocusActionInfo.builder().setSourceAction(FocusActionInfo.LOGICAL_NAVIGATION).build(),1)
    window.childReads=0;document.childReads=0;leaf.childReads=0
    return saved
  }
  private fun restore(record: FocusActionRecord)=FocusActionRecord.getFocusableNodeFromFocusRecord(window,finder,record)

  @Test fun stableWebHistoryKeepsEveryAncestorForScrollRecovery() {
    val path=record().nodePathDescription
    assertTrue(path.usesWebIdentity())
    assertTrue(path.containsNodeByHashAndIdentity(leaf))
    assertTrue(path.containsNodeByHashAndIdentity(document))
    assertTrue(path.containsNodeByHashAndIdentity(window))
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
    document.clearChildren();document.add(replacement)
    assertEquals(replacement,restore(saved))
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
    assertNotNull(saved.nodePathDescription.findNodeToRefocus(window,finder))
  }
  @Test fun htmlProvidersWithoutAWebViewBoundaryKeepStructuralHistory() {
    document.className="android.widget.FrameLayout"
    val saved=record()
    assertFalse(saved.nodePathDescription.usesWebIdentity())
    assertNotNull(saved.nodePathDescription.findNodeToRefocus(window,finder))
  }
  @Test fun regeneratedChildIdsRestoreByPathInsideTheSameDocument() {
    val saved=record();leaf.live=false
    val replacement=Node("item",html=true,stableId="regenerated-item")
    document.clearChildren();document.add(replacement)
    assertEquals(replacement,restore(saved))
  }
  @Test fun regeneratedNestedIdsKeepTheStructuralFallback() {
    val group=Node("group","android.view.View",true)
    document.clearChildren();document.add(group);group.add(leaf)
    val saved=record();leaf.live=false;group.live=false
    val newGroup=Node("group","android.view.View",true,"regenerated-group")
    val replacement=Node("item",html=true,stableId="regenerated-item")
    document.clearChildren();document.add(newGroup);newGroup.add(replacement)
    assertEquals(replacement,restore(saved))
  }
  @Test fun anotherDocumentCannotSupplyAnIdenticalIdOrStructuralMatch() {
    val saved=record();leaf.live=false;document.clearChildren()
    val other=Node("document","android.webkit.WebView",true,"other-document")
    other.add(Node("item",html=true));window.add(other)
    assertEquals(document,restore(saved))
    assertEquals(0,other.childReads)
  }
  @Test fun stillLiveNodeMovedToAnotherDocumentIsNotRestored() {
    val saved=record();document.clearChildren()
    val other=Node("other","android.webkit.WebView",true)
    other.add(leaf);window.add(other)
    assertEquals(document,restore(saved))
    assertEquals(0,other.childReads)
  }
  @Test fun removedDocumentCannotRestoreEvenIfOldLeafStillRefreshes() {
    val saved=record();document.live=false
    assertNull(restore(saved))
  }
  @Test fun nativeRecordsKeepTheirWindowWideStableIdRecovery() {
    val native=Node("native");window.clearChildren();window.add(native)
    val saved=record(native);native.live=false
    val replacement=Node("native");window.clearChildren();window.add(replacement)
    assertFalse(saved.nodePathDescription.usesWebIdentity())
    assertEquals(replacement,restore(saved))
  }
}
