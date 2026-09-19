package com.example.emoo

import com.example.emoo.send.GifEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

/** GIF 编码器正确性：产物为合法 GIF89a，可被标准解码器读取，帧数与尺寸正确 */
class GifEncoderTest {

    @Test
    fun encodesAnimatedGifReadableByImageIO() {
        val w = 64
        val h = 64
        val out = ByteArrayOutputStream()
        val encoder = GifEncoder()
        assertTrue(encoder.start(out))
        encoder.setRepeat(0)
        for (f in 0 until 3) {
            val px = IntArray(w * h)
            for (i in px.indices) {
                val r = (f * 80 + (i % w) * 2).coerceAtMost(255)
                val g = (i / w) * 3
                px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or (255 - f * 60)
            }
            encoder.setDelay(100)
            assertTrue(encoder.addFrame(px, w, h))
        }
        assertTrue(encoder.finish())

        val bytes = out.toByteArray()
        assertTrue(bytes.size > 100)
        assertEquals('G'.code.toByte(), bytes[0])
        assertEquals('I'.code.toByte(), bytes[1])

        // 结构解析： Graphic Control Ext + Image Descriptor 数量应为 3
        val frameCount = countImageDescriptors(bytes)
        assertEquals(3, frameCount)

        // 标准解码器应能读出首帧
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
        val image = reader.read(0)
        assertEquals(w, image.width)
        assertEquals(h, image.height)
        reader.dispose()
    }

    private fun countImageDescriptors(bytes: ByteArray): Int {
        var count = 0
        var i = 13 // header(6) + LSD(7)
        // 跳过全局颜色表
        val packed = bytes[10].toInt() and 0xFF
        if (packed and 0x80 != 0) {
            val sizePow = packed and 0x07
            i += 3 * (1 shl (sizePow + 1))
        }
        while (i < bytes.size) {
            when (bytes[i].toInt() and 0xFF) {
                0x21 -> { // extension: skip sub-blocks
                    i += 2
                    while (i < bytes.size && bytes[i].toInt() != 0) {
                        i += (bytes[i].toInt() and 0xFF) + 1
                    }
                    i++
                }
                0x2C -> { // image descriptor
                    count++
                    i += 9 // separator + 4×2 坐标尺寸 → i 指向 packed 字节
                    val packedLocal = bytes[i].toInt() and 0xFF
                    i++ // 越过 packed
                    if (packedLocal and 0x80 != 0) {
                        i += 3 * (1 shl ((packedLocal and 0x07) + 1))
                    }
                    i++ // 越过 LZW min code size
                    while (i < bytes.size && bytes[i].toInt() != 0) {
                        i += (bytes[i].toInt() and 0xFF) + 1
                    }
                    i++
                }
                else -> return count
            }
        }
        return count
    }
}
