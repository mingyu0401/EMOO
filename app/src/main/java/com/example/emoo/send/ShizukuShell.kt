package com.example.emoo.send

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku shell 通道：以 shell（adb）身份执行命令，供拖拽注入与
 * 纯 Shizuku 发送（窗口检测 / uiautomator 节点定位 / input 文本输入）共用。
 *
 * 常驻 shell：优先把命令写进 [ShellUserService] 持有的同一个 `sh` 进程
 * （所有 input / uiautomator dump 共用，免去每条命令 binder + fork 的数百毫秒开销）。
 * UserService 未就绪时回退到每条命令 newProcess 的旧路径。
 *
 * 大输出的 dumpsys（window / SurfaceFlinger，可能超过 1MB binder 事务上限）
 * 仍走 newProcess 流式读取，不经常驻 shell。
 *
 * binder 优先走 ContentProvider 通道（ColorOS 等 ROM 会阻止 shizuku_server
 * 注册进 ServiceManager，SystemServiceHelper 查询会拿到 null）。
 */
object ShizukuShell {

    private const val TAG = "ShizukuShell"
    private const val PKG = "com.example.emoo"
    private const val SHELL_SERVICE_CLASS = "com.example.emoo.send.ShellUserService"
    private const val RC_PREFIX = "__EMOO_RC__"
    private const val BIND_TIMEOUT_MS = 5_000L

    @Volatile
    private var shellService: IShellService? = null

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

    /** 取得常驻 shell 的 UserService binder；未就绪/绑定失败返回 null（调用方回退 newProcess） */
    private fun shell(): IShellService? {
        shellService?.let {
            if (it.asBinder().pingBinder()) return it
            shellService = null
        }
        if (!try { Shizuku.pingBinder() } catch (_: Exception) { false }) return null
        val latch = CountDownLatch(1)
        var bound: IShellService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                bound = binder?.let { IShellService.Stub.asInterface(it) }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                shellService = null
                latch.countDown()
            }

            override fun onBindingDied(name: ComponentName?) {
                shellService = null
                latch.countDown()
            }

            override fun onNullBinding(name: ComponentName?) = latch.countDown()
        }
        return try {
            val args = Shizuku.UserServiceArgs(ComponentName(PKG, SHELL_SERVICE_CLASS))
                .daemon(true)
                .processNameSuffix("shell")
                .debuggable(false)
                .version(1)
            Shizuku.bindUserService(args, conn)
            latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            shellService = bound
            if (bound != null) Log.i(TAG, "常驻 shell UserService 已绑定") else Log.w(TAG, "UserService 绑定超时")
            bound
        } catch (e: Exception) {
            Log.w(TAG, "绑定 UserService 失败，回退 newProcess", e)
            null
        }
    }

    /** 把参数拼成一条 shell 命令行，含特殊字符的参数用单引号包裹 */
    private fun toCommandLine(args: Array<out String>): String =
        args.joinToString(" ") { a ->
            if (a.isNotEmpty() && a.all { it.isLetterOrDigit() || it in "._/@:-=" }) {
                a
            } else {
                "'" + a.replace("'", "'\\''") + "'"
            }
        }

    /**
     * 在常驻 shell 中执行一行命令，返回 (stdout, exitCode)；不可用/超时返回 null。
     * 常驻 shell 未就绪时回退到 newProcess 的 `sh -c`。
     */
    private fun runShellResult(command: String, timeoutMs: Long): Pair<String, Int>? {
        shell()?.let { svc ->
            val raw = try {
                svc.exec(command, timeoutMs)
            } catch (e: Exception) {
                Log.w(TAG, "常驻 shell 执行异常，回退 newProcess: $command", e)
                shellService = null
                null
            }
            if (raw != null) {
                val idx = raw.lastIndexOf("\n$RC_PREFIX")
                return if (idx >= 0) {
                    raw.substring(0, idx) to (raw.substring(idx + RC_PREFIX.length + 1).trim().toIntOrNull() ?: -1)
                } else {
                    raw to -1
                }
            }
        }
        return newProcessShell(command, timeoutMs)
    }

    /** 回退路径：newProcess 跑 `sh -c command`，流式读 stdout */
    private fun newProcessShell(command: String, timeoutMs: Long): Pair<String, Int>? {
        val process = try {
            service()?.newProcess(arrayOf("sh", "-c", command), null, null) ?: return null
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku 执行失败: $command", e)
            return null
        }
        return try {
            val builder = StringBuilder()
            val reader = Thread {
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
            while (reader.isAlive && System.currentTimeMillis() < deadline) reader.join(100)
            if (reader.isAlive) {
                Log.w(TAG, "Shizuku 命令超时: $command")
                return null
            }
            builder.toString() to process.waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku 命令异常: $command", e)
            null
        } finally {
            if (process.alive()) process.destroy()
        }
    }

    /** 在常驻 shell 中执行一行命令并返回 stdout（失败/超时返回 null） */
    fun runShell(command: String, timeoutMs: Long = 10_000L): String? =
        runShellResult(command, timeoutMs)?.first

    /** 以 shell 身份执行命令并等待退出，返回退出码是否为 0 */
    fun exec(vararg args: String): Boolean {
        val (out, rc) = runShellResult(toCommandLine(args), 10_000L) ?: return false
        return rc == 0
    }

    /**
     * 以 shell 身份执行命令并捕获 stdout。用于**大输出**命令
     * （dumpsys window / SurfaceFlinger，可能超过 binder 1MB 事务上限），
     * 走 newProcess 流式读取，不经常驻 shell。[timeoutMs] 超时后强制销毁进程。
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
