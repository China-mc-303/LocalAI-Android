package com.buxingjie.localai.tools

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.buxingjie.localai.service.LocalAiAccessibilityService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 手机操控执行器。所有操作都通过无障碍服务完成，用户可随时点悬浮窗“紧急停止”。
 */
object PhoneControl {

    @Volatile
    var aborted: Boolean = false
        private set

    fun reset() {
        aborted = false
    }

    /** 紧急停止：停止当前生成，并拒绝后续一切手机操作。 */
    fun abort(context: Context) {
        aborted = true
        context.sendBroadcast(Intent(ACTION_EMERGENCY_STOP).setPackage(context.packageName))
    }

    fun serviceReady(): Boolean = LocalAiAccessibilityService.connected

    fun overlayAllowed(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isPhoneAction(name: String?): Boolean =
        name in setOf("tap", "swipe", "type", "back", "home", "open_app", "screen_text")

    /** 执行一个手机操作，返回给模型看的自然语言结果。 */
    fun run(context: Context, call: JsonObject): String {
        if (aborted) return "操作已被用户紧急停止"
        val service = LocalAiAccessibilityService.instance
            ?: return "无障碍服务未开启，无法操控手机。请到设置中开启“本地AI 辅助操作”权限"
        val action = call["tool"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return try {
            when (action) {
                "open_app" -> openApp(context, call.str("app"))
                "tap" -> {
                    val x = call.flt("x") ?: return "tap 缺少 x 坐标（0~1）"
                    val y = call.flt("y") ?: return "tap 缺少 y 坐标（0~1）"
                    if (service.tap(x, y)) "已点击 (${fmt(x)}, ${fmt(y)})" else "点击失败"
                }
                "swipe" -> {
                    val x1 = call.flt("x1") ?: call.flt("fromX")
                    val y1 = call.flt("y1") ?: call.flt("fromY")
                    val x2 = call.flt("x2") ?: call.flt("toX")
                    val y2 = call.flt("y2") ?: call.flt("toY")
                    if (x1 == null || y1 == null || x2 == null || y2 == null) {
                        "swipe 需要 x1,y1,x2,y2（0~1）"
                    } else if (service.swipe(x1, y1, x2, y2)) "已滑动" else "滑动失败"
                }
                "type" -> service.typeText(call.str("text"))
                "back" -> if (service.pressBack()) "已返回" else "返回失败"
                "home" -> if (service.pressHome()) "已回到桌面" else "回桌面失败"
                "screen_text" -> service.screenText()
                else -> "未知手机操作：$action"
            }
        } catch (t: Throwable) {
            "操作出错：${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun openApp(context: Context, query: String): String {
        if (query.isBlank()) return "open_app 需要 app 名称"
        val manager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = runCatching { manager.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        val target = activities.firstOrNull { info ->
            val label = info.loadLabel(manager).toString()
            label.equals(query, ignoreCase = true) || label.contains(query, ignoreCase = true) ||
                info.activityInfo.packageName.contains(query, ignoreCase = true)
        } ?: return "没有找到应用“$query”，请确认已安装"
        val launch = manager.getLaunchIntentForPackage(target.activityInfo.packageName) ?: return "无法启动 ${target.loadLabel(manager)}"
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(launch)
        return "已打开 ${target.loadLabel(manager)}"
    }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.flt(key: String): Float? =
        runCatching { this[key]?.jsonPrimitive?.floatOrNull }.getOrNull()
            ?: runCatching { this[key]?.jsonPrimitive?.contentOrNull?.toFloat() }.getOrNull()

    private fun fmt(value: Float) = String.format(java.util.Locale.US, "%.2f", value)

    const val ACTION_EMERGENCY_STOP = "com.buxingjie.localai.ACTION_EMERGENCY_STOP"
}
