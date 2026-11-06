package com.buxingjie.localai.tools

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Shizuku 桥接：授权后可以让终端命令以 shell（uid 2000）身份运行，
 * 从而读写更多系统目录、执行部分 adb 级命令。
 */
object ShizukuShell {

    const val REQUEST_CODE = 2001

    /** Shizuku 服务是否在运行。 */
    fun installed(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 是否已拿到 Shizuku 的 shell 权限。 */
    fun granted(): Boolean = installed() && runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 当前运行身份，未授权时为应用自身 uid。 */
    fun uidLabel(): String {
        if (!granted()) return "普通应用权限"
        val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        return if (uid == 2000) "shell 权限" else "uid $uid"
    }

    /** 弹出 Shizuku 授权对话框，结果通过 [Shizuku.OnRequestPermissionResultListener] 回调。 */
    fun requestPermission() {
        if (!installed()) return
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
    }

    /** 拉起 Shizuku 管理器，方便用户安装或启动服务。 */
    fun managerPackage(): String = "moe.shizuku.privileged.api"

    /** 以 Shizuku 身份启动一个常驻 sh，供终端使用。 */
    fun newShell(workDir: File?): Process? = runCatching {
        // Shizuku 13 起 newProcess 被标记为 private，这里用反射调用，仍然是官方同一套远程进程
        val stringArray = arrayOf<String>().javaClass
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            stringArray,
            stringArray,
            String::class.java,
        )
        method.isAccessible = true
        val args = arrayOf<Any?>(arrayOf("/system/bin/sh"), null, workDir?.absolutePath ?: "/")
        method.invoke(null, *args) as? Process
    }.getOrNull()
}
