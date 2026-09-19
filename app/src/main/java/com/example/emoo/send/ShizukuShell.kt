package com.example.emoo.send

import android.os.ParcelFileDescriptor
import android.util.Log
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Shizuku shell 通道：以 shell（adb）身份执行命令，供拖拽注入与
 * 纯 Shizuku 发送（窗口检测 / uiautomator 节点定位 / input 文本输入）共用。
 *
 * binder 优先走 ContentProvider 通道（ColorOS 等 ROM 会阻止 shizuku_server
 * 注册进 ServiceManager，SystemServiceHelper 查询会拿到 null）。
 */
object ShizukuShell {

    private const val TAG = "ShizukuShell"

    private fun service(): IShizukuService? {
        val raw = try {
            Shizuku.getBinder()
        } catch (_: Exception) {
            null
        } ?: try {
            SystemServiceHelper.getSystemService("shizuku")
        } catch (_: Exception) {
            null
        } ?: return null
        return IShizukuService.Stub.asInterface(ShizukuBinderWrapper(raw))
    }

    /** 以 shell 身份执行命令并等待退出，返回退出码是否为 0 */
    fun exec(vararg args: String): Boolean {
        val process = try {
            service()?.newProcess(args, null, null) ?: return false
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku 执行失败: ${args.joinToString(" ")}", e)
            return false
        }
        return try {
            process.waitFor() == 0
        } finally {
            if (process.alive()) process.destroy()
        }
    }

    /**
     * 以 shell 身份执行命令并捕获 stdout（dumpsys / uiautomator dump 等）。
     * [timeoutMs] 超时后强制销毁进程（uiautomator dump 偶发挂起）。
     */
    fun execWithOutput(vararg args: String, timeoutMs: Long = 10_000L): String? {
        val process = try {
            service()?.newProcess(args, null, null) ?: return null
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku 执行失败: ${args.joinToString(" ")}", e)
            return null
        }
        var reader: Thread? = null
        return try {
            // 先在独立线程读完 stdout 再 waitFor，避免管道缓冲区写满死锁。
            // IRemoteProcess.getInputStream 返回 ParcelFileDescriptor，需包一层流
            val builder = StringBuilder()
            reader = Thread {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(process.inputStream).use { input ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            builder.append(String(buf, 0, n, Charsets.UTF_8))
                        }
                    }
                } catch (_: Exception) {
                }
            }
            reader.start()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (reader.isAlive && System.currentTimeMillis() < deadline) {
                reader.join(100)
            }
            if (reader.isAlive) {
                Log.w(TAG, "Shizuku 命令超时: ${args.joinToString(" ")}")
                return null
            }
            if (process.waitFor() != 0) return null
            builder.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku 命令异常: ${args.joinToString(" ")}", e)
            null
        } finally {
            if (process.alive()) process.destroy()
        }
    }
}
