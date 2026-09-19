package com.example.emoo.send

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 以 shell（adb）身份运行在 Shizuku UserService 远程进程里的常驻 shell。
 *
 * 进程内只 fork 一次 `sh`，之后每条命令都写进它的 stdin，并紧跟
 * `rc=$?` 先存下退出码、再打一个空行保证换行、最后 `echo <token>:$rc` 作为哨兵；
 * 从 stdout 读到哨兵行即拿到该命令的输出与退出码。相比每条 input/dump 都
 * newProcess（每次 binder + fork，数百毫秒），常驻 shell 把单次命令降到毫秒级。
 *
 * 命令超时（uiautomator dump 偶发挂起）或进程已死时销毁重建，避免污染后续命令。
 */
class ShellUserService : IShellService.Stub() {

    private companion object {
        const val TAG = "ShellUserService"
        const val RC_PREFIX = "__EMOO_RC__"
        const val MAX_OUTPUT = 900_000
    }

    private val lock = ReentrantLock()
    private val lines = LinkedBlockingQueue<String>()

    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private var reader: Thread? = null
    private var token = 0

    /** 确保常驻 sh 存活，返回是否可用 */
    private fun ensureShell(): Boolean {
        if (process?.isAlive == true) return true
        destroyShell()
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh"))
            process = p
            stdin = BufferedWriter(OutputStreamWriter(p.outputStream))
            val out = BufferedReader(InputStreamReader(p.inputStream))
            reader = Thread {
                try {
                    while (true) {
                        val line = out.readLine() ?: break
                        lines.put(line)
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true; start() }
            Log.i(TAG, "常驻 shell 已启动")
            true
        } catch (e: Exception) {
            Log.w(TAG, "启动常驻 shell 失败", e)
            false
        }
    }

    private fun destroyShell() {
        try { reader?.interrupt() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        process = null
        stdin = null
        reader = null
        lines.clear()
    }

    override fun exec(command: String, timeoutMs: Long): String? {
        lock.lock()
        try {
            if (!ensureShell()) return null
            val mark = "$RC_PREFIX${token++}"
            // 先存退出码，再空行保证换行，最后打哨兵——空行会覆盖 $?，故必须先用变量存下
            val payload = "$command\nrc9=\$?\necho\necho $mark:\$rc9\n"
            val out = stdin ?: return null
            try {
                lines.clear()
                out.write(payload)
                out.flush()
            } catch (e: Exception) {
                Log.w(TAG, "写入常驻 shell 失败，重建", e)
                destroyShell()
                return null
            }

            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val remain = deadline - System.currentTimeMillis()
                val line = lines.poll(minOf(remain, 200L), TimeUnit.MILLISECONDS)
                if (line == null) {
                    if (process?.isAlive != true) {
                        Log.w(TAG, "常驻 shell 已死，重建")
                        destroyShell()
                        return null
                    }
                    continue
                }
                if (line.startsWith("$mark:")) {
                    val rc = line.substringAfter("$mark:").trim().toIntOrNull() ?: -1
                    sb.append('\n').append(RC_PREFIX).append(rc)
                    return sb.toString()
                }
                if (sb.length < MAX_OUTPUT) sb.append(line).append('\n')
            }
            // 超时：shell 很可能被卡死，销毁重建
            Log.w(TAG, "命令超时(${timeoutMs}ms)，重建常驻 shell: $command")
            destroyShell()
            return null
        } finally {
            lock.unlock()
        }
    }

    override fun reset() {
        lock.lock()
        try { destroyShell() } finally { lock.unlock() }
    }
}
