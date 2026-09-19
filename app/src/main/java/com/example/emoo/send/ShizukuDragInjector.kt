package com.example.emoo.send

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 拖拽注入器：以 shell（adb）身份执行系统的 `input` 命令模拟
 * “长按 → 拖动 → 抬手”，为无障碍 dispatchGesture 之外的第二条触摸注入通道。
 *
 * 说明：Android（含 shell/root）没有任何“直接向其他应用投递拖放载荷”的 API——
 * 拖放必须由拖拽源应用经输入管线的真实触摸流发起。因此 Shizuku 无法让 QQ
 * “直接接收”图片，但它注入的 shell 级 input 事件不经过无障碍通道，部分 ROM
 * 对无障碍手势注入有限制/取消行为，走 shell 注入可能绕开此类干扰。
 * 拖拽会话仍由本应用图片格子的长按拖拽源（startDragAndDrop）发起。
 */
object ShizukuDragInjector {

    private const val TAG = "ShizukuDragInjector"

    /** Shizuku 运行中且已授权时可用（首次调用会触发授权请求弹窗/通知） */
    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    /** Shizuku 服务是否在运行（不要求已授权，供设置页显示状态） */
    fun binderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Exception) {
        false
    }

    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    /** 拉起 Shizuku 授权弹窗；授权结果在回到应用后重新检查即可 */
    fun requestPermission(context: Context) {
        try {
            Shizuku.requestPermission(0)
        } catch (e: Exception) {
            Log.w(TAG, "请求 Shizuku 授权失败", e)
        }
    }

    /**
     * 执行 `input draganddrop`：单条命令完成“长按按下→移动→抬起”。
     * 系统实现是 DOWN 后固定长按约 2×longPressTimeout，再在 [durationMs] 内
     * 移动到终点，因此 [durationMs] 越小移动越快——快速移动可避免 ColorOS 把
     * 慢速拖拽识别成窗口管理手势。阻塞至命令退出，返回手势是否完整执行。
     */
    fun dragAndDrop(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long = 400L
    ): Boolean {
        return try {
            ShizukuShell.exec(
                "input", "draganddrop",
                fromX.toInt().toString(), fromY.toInt().toString(),
                toX.toInt().toString(), toY.toInt().toString(),
                durationMs.toString()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku input draganddrop 执行失败", e)
            false
        }
    }

    /**
     * 兜底：draganddrop 长按时长不满足拖拽源检测时，用逐条
     * `input motionevent`（DOWN → MOVE… → UP）手工拼出手势。
     * 每条命令独立进程有数百毫秒开销，步数不宜多；长按 0.7 秒触发拖拽源，
     * MOVE 段连发并在总时长上贴近 0.5 秒——快速移动躲开 ColorOS 对慢速
     * 窗口拖动的识别。
     */
    fun dragAndDropManual(fromX: Float, fromY: Float, toX: Float, toY: Float): Boolean {
        try {
            val steps = 6
            val okDown = ShizukuShell.exec(
                "input", "motionevent", "DOWN",
                fromX.toInt().toString(), fromY.toInt().toString()
            )
            if (!okDown) return false
            Thread.sleep(700) // 长按 0.7 秒触发拖拽源
            for (i in 1..steps) {
                val t = i / steps.toFloat()
                val x = fromX + (toX - fromX) * t
                val y = fromY + (toY - fromY) * t
                if (!ShizukuShell.exec(
                        "input", "motionevent", "MOVE",
                        x.toInt().toString(), y.toInt().toString()
                    )
                ) {
                    return false
                }
                if (i < steps) Thread.sleep(70)
            }
            Thread.sleep(100) // 短暂悬停供 drop 目标识别
            return ShizukuShell.exec(
                "input", "motionevent", "UP",
                toX.toInt().toString(), toY.toInt().toString()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku motionevent 拖拽执行失败", e)
            return false
        }
    }
}
