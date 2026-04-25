package com.aura.agent.actions

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * AccessibilityService that gives Aura read/write access to on-screen UI.
 *
 * Security model:
 *   - Only used when explicitly requested by a confirmed agent action.
 *   - Screen content is processed locally, never sent to cloud.
 *   - The service reports its state via [screenContent] so the agent
 *     can observe the result of its actions.
 *
 * AndroidManifest requires:
 *   <service android:name=".AuraAccessibilityService"
 *            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
 *     <intent-filter>
 *       <action android:name="android.accessibilityservice.AccessibilityService"/>
 *     </intent-filter>
 *     <meta-data android:name="android.accessibilityservice"
 *                android:resource="@xml/accessibility_service_config"/>
 *   </service>
 */
class AuraAccessibilityService : AccessibilityService() {

    companion object {
        private val _screenContent = MutableStateFlow<ScreenSnapshot?>(null)
        val screenContent: StateFlow<ScreenSnapshot?> = _screenContent

        private var instance: AuraAccessibilityService? = null

        val isRunning: Boolean get() = instance != null

        /** Programmatically click the first node matching [text]. */
        fun clickNode(text: String): Boolean {
            val svc = instance ?: return false
            return svc.findAndClick(text)
        }

        /** Type [text] into the currently focused input field. */
        fun typeText(text: String): Boolean {
            val svc = instance ?: return false
            val focused = svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            return if (focused != null) {
                val args = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } else false
        }
    }

    override fun onServiceConnected() {
        instance = this
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        Timber.i("AuraAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            captureScreen()
        }
    }

    override fun onInterrupt() {
        Timber.w("AuraAccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    private fun captureScreen() {
        val root = rootInActiveWindow ?: return
        val packageName = root.packageName?.toString() ?: return
        val texts = mutableListOf<String>()
        collectTexts(root, texts)
        root.recycle()
        _screenContent.value = ScreenSnapshot(packageName, texts)
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int = 0) {
        if (depth > 12) return
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank()) out.add(text)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> collectTexts(child, out, depth + 1); child.recycle() }
        }
    }

    private fun findAndClick(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        val clicked = nodes.any { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        root.recycle()
        return clicked
    }
}

data class ScreenSnapshot(
    val packageName: String,
    val visibleTexts: List<String>,
) {
    fun toReadableString() = "App: $packageName\nContent:\n${visibleTexts.joinToString("\n")}"
}
