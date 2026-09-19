package com.example.emoo.send

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 大 GIF 发送前压缩器。
 *
 * QQ 对拖放收到的 GIF 有消息类型判断：文件过大（用户实测约 5MB 以上）会按
 * “图片消息”而非“表情”发出。此压缩器把超过阈值的 GIF 固定抽 24 帧
 * （帧间隔固定 42ms）、逐级缩小像素尺寸重编码到目标体积以下，使其保持表情形态。
 * 小于阈值的原样返回不处理。
 */
object GifCompressor {

    private const val TAG = "GifCompressor"

    /** 超过此大小才压缩 */
    const val COMPRESS_THRESHOLD_BYTES: Long = 5L * 1024 * 1024

    /** 压缩后的目标上限（5MB 以下） */
    private const val TARGET_BYTES: Long = 5L * 1024 * 1024

    /** 固定输出帧数 */
    private const val MAX_FRAMES = 1000

    /** 固定帧间隔（42ms ≈ 24fps） */
    private const val FRAME_INTERVAL_MS = 42L

    /** 尝试的降采样最大边长，逐级减小直至文件达标 */
    private val MAX_DIM_STEPS = intArrayOf(640, 480, 360, 280, 200, 150)

    /**
     * 若 [source] 超过阈值则压缩到 [outDir] 下，返回压缩产物；
     * 未超限返回 null（无需压缩）；无法解码/压缩失败也返回 null（调用方回退原图）。
     */
    suspend fun compressIfNeeded(source: File, outDir: File): File? =
        withContext(Dispatchers.Default) {
            if (source.length() <= COMPRESS_THRESHOLD_BYTES) return@withContext null
            outDir.mkdirs()
            val baseName = source.nameWithoutExtension
            for (maxDim in MAX_DIM_STEPS) {
                val outFile = File(outDir, "${baseName}_emoo.gif")
                val encoded = runCatching { encode(source, outFile, maxDim) }.getOrDefault(false)
                if (encoded && outFile.exists() && outFile.length() > 0) {
                    if (outFile.length() < TARGET_BYTES) return@withContext outFile
                    // 仍未达标则删掉继续缩小尺寸
                    outFile.delete()
                } else {
                    outFile.delete()
                }
            }
            null
        }

    /** 用 Movie 解帧 → 缩放 → GifEncoder 重编码（固定 [FIXED_FRAMES] 帧、[FRAME_INTERVAL_MS] 帧间隔） */
    private fun encode(source: File, outFile: File, maxDim: Int): Boolean {
        val movie = Movie.decodeFile(source.absolutePath) ?: return false
        val srcW = movie.width().coerceAtLeast(1)
        val srcH = movie.height().coerceAtLeast(1)
        val scale = minOf(1f, maxDim.toFloat() / maxOf(srcW, srcH).toFloat())
        val outW = (srcW * scale).toInt().coerceIn(1, srcW)
        val outH = (srcH * scale).toInt().coerceIn(1, srcH)

        val pixels = IntArray(outW * outH)
        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(outW.toFloat() / srcW, outH.toFloat() / srcH)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

        var outFileTmp = File(outFile.parentFile, outFile.name + ".tmp")
        try {
            java.io.FileOutputStream(outFileTmp).use { fos ->
                val bos = ByteArrayOutputStream()
                val encoder = GifEncoder()
                if (!encoder.start(bos)) return false
                encoder.setRepeat(0)

                var frames = 0
                var t = 0L
                while (frames < MAX_FRAMES && t < 30_000L) {
                    if (!movie.setTime(t.toInt())) break
                    bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
                    movie.draw(canvas, 0f, 0f, paint)
                    bitmap.getPixels(pixels, 0, outW, 0, 0, outW, outH)
                    encoder.setDelay(FRAME_INTERVAL_MS.toInt())
                    if (!encoder.addFrame(pixels, outW, outH)) break
                    frames++
                    t += FRAME_INTERVAL_MS
                }
                if (frames == 0) return false
                encoder.finish()

                bos.writeTo(fos)
                fos.fd.sync()
            }
            if (!outFileTmp.renameTo(outFile)) {
                outFileTmp.copyTo(outFile, overwrite = true)
                outFileTmp.delete()
            }
            return true
        } finally {
            bitmap.recycle()
            if (outFileTmp.exists()) outFileTmp.delete()
        }
    }
}
