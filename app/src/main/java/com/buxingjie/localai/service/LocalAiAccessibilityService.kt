package com.buxingjie.localai.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 供 AI 读取屏幕与执行点击/滑动/返回等操作的无障碍服务。
 */
class LocalAiAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun screenText(): String {
        val sb = StringBuilder()
        val width = resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
        val height = resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(1f)
        var count = 0

        val roots = ArrayList<AccessibilityNodeInfo>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            windows.forEach { window -> window.root?.let { roots.add(it) } }
        }
        if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || count >= 120 || sb.length > 4000) return
            val label = buildString {
                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()
                if (!text.isNullOrEmpty() && text != desc) append(text)
                if (!desc.isNullOrEmpty()) {
                    if (isNotEmpty()) append(' ')
                    append(desc)
                }
            }.replace(Regex("\n+"), " ")
            if (label.isNotEmpty()) {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    val cx = ((rect.left + rect.right) / 2f / width).coerceIn(0f, 1f)
                    val cy = ((rect.top + rect.bottom) / 2f / height).coerceIn(0f, 1f)
                    sb.append(String.format(java.util.Locale.US, "[%.2f,%.2f] %s", cx, cy, label.take(60)))
                    sb.append('\n')
                    count++
                }
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i), depth + 1)
                if (count >= 120 || sb.length > 4000) return
            }
        }

        roots.forEach { walk(it, 0) }
        return if (sb.isBlank()) {
            "（当前屏幕没有可读取的文字，可能是不支持无障碍读取的页面）"
        } else {
            sb.toString().trim()
        }
    }

    fun tap(nx: Float, ny: Float): Boolean {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        val x = nx.coerceIn(0f, 1f) * width
        val y = ny.coerceIn(0f, 1f) * height
        return gesture { path -> path.moveTo(x, y) }
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        return gesture(duration = 320) { path ->
            path.moveTo(x1.coerceIn(0f, 1f) * width, y1.coerceIn(0f, 1f) * height)
            path.lineTo(x2.coerceIn(0f, 1f) * width, y2.coerceIn(0f, 1f) * height)
        }
    }

    private fun gesture(duration: Long = 80, build: (Path) -> Unit): Boolean {
        val path = Path().apply(build)
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val description = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(description, null, null)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun typeText(text: String): String {
        val root = rootInActiveWindow ?: return "无法读取当前窗口"
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root)
            ?: return "当前页面没有找到输入框，请先点击输入框再让 AI 输入"
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            "已输入文字"
        } else {
            focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "已输入文字"
            else "输入失败，目标输入框可能不允许写入"
        }
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findEditable(child)?.let { return it }
            }
        }
        return null
    }

    companion object {
        @Volatile
        var instance: LocalAiAccessibilityService? = null
            private set

        val connected: Boolean get() = instance != null
    }
}
