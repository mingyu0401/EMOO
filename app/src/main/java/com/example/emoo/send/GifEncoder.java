package com.example.emoo.send;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;

/**
 * 动画 GIF 编码器（LZW 编码 + NeuQuant 调色板量化，GIF89a）。
 * 纯 Java 实现、不依赖 Android 类，便于 JVM 单元测试。
 *
 * 输入为 int[]（0xAARRGGBB）帧像素序列。首帧量化生成全局 256 色调色板，
 * 后续帧通过预计算的 5bit/通道 最近色查找表映射（O(1)/像素），
 * 调色板全片一致，避免逐帧量化导致的色彩闪烁。
 */
public class GifEncoder {

    private OutputStream out;
    private boolean started;
    private boolean firstFrame = true;

    private int width;
    private int height;
    private int delay = 100;
    private int repeat = 0;

    // 全局调色板（RGB 三元组，256 项）
    private byte[] palette;
    // 5bit/通道 最近色查找表：32768 项
    private int[] nearestLut;

    /** 设置每帧延后显示的毫秒数（addFrame 前调用） */
    public void setDelay(int ms) {
        delay = ms;
    }

    /** 循环次数：0 = 无限循环，-1 = 不循环 */
    public void setRepeat(int repeat) {
        this.repeat = repeat;
    }

    public boolean start(OutputStream os) throws IOException {
        out = os;
        out.write("GIF89a".getBytes("US-ASCII"));
        started = true;
        return true;
    }

    /** 添加一帧。像素长度须为 w*h。全部数据立即写入流，不保留引用 */
    public boolean addFrame(int[] argb, int w, int h) throws IOException {
        if (!started) return false;
        if (argb.length != w * h) return false;
        width = w;
        height = h;
        if (firstFrame) {
            buildPalette(argb);
            writeLogicalScreenDescriptor();
            writePalette();
            if (repeat >= 0) writeNetscapeExt();
        }
        int[] indices = mapToPalette(argb);
        writeGraphicControlExt();
        writeImageDescriptor();
        writeLzw(indices);
        firstFrame = false;
        return true;
    }

    public boolean finish() throws IOException {
        if (!started) return false;
        out.write(0x3B); // trailer
        out.flush();
        started = false;
        return true;
    }

    // ============================ 结构块 ============================

    private void writeLogicalScreenDescriptor() throws IOException {
        int palSize = 7; // 2^(7+1) = 256
        writeShort(width);
        writeShort(height);
        // 全局颜色表标志 1 | 颜色分辨率 7 | 排序 0 | 全局颜色表大小 7
        out.write(0x80 | palSize);
        out.write(0); // 背景色索引
        out.write(0); // 像素宽高比
    }

    private void writePalette() throws IOException {
        out.write(palette, 0, palette.length);
    }

    private void writeGraphicControlExt() throws IOException {
        out.write(0x21);
        out.write(0xF9);
        out.write(0x04);
        // 处置方法 1（不处置）| 无透明色
        out.write(0x04);
        writeShort(delay / 10); // 单位为 1/100 秒
        out.write(0);           // 透明色索引（未使用）
        out.write(0);
    }

    private void writeImageDescriptor() throws IOException {
        out.write(0x2C);
        writeShort(0);
        writeShort(0);
        writeShort(width);
        writeShort(height);
        out.write(0x00); // 无局部颜色表、非隔行
    }

    private void writeNetscapeExt() throws IOException {
        out.write(0x21);
        out.write(0xFF);
        out.write(0x0B);
        out.write("NETSCAPE2.0".getBytes("US-ASCII"));
        out.write(0x03);
        out.write(0x01);
        writeShort(repeat);
        out.write(0x00);
    }

    private void writeLzw(int[] indices) throws IOException {
        LzwEncoder encoder = new LzwEncoder(width, height, indices, 8);
        encoder.encode(out);
        out.write(0x00); // 块结束符
    }

    private void writeShort(int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    // ============================ 调色板与映射 ============================

    /** 用首帧像素训练 NeuQuant 调色板，并预计算最近色查找表 */
    private void buildPalette(int[] argb) throws IOException {
        try {
            int sample = 10;
            if (argb.length < 1509) sample = 1;
            NeuQuant nq = new NeuQuant(argb, sample);
            nq.learn();
            palette = nq.palette();
        } catch (Exception e) {
            palette = uniformPalette();
        }
        nearestLut = new int[1 << 15];
        java.util.Arrays.fill(nearestLut, -1);
        for (int r5 = 0; r5 < 32; r5++) {
            for (int g5 = 0; g5 < 32; g5++) {
                for (int b5 = 0; b5 < 32; b5++) {
                    int r = (r5 << 3) | (r5 >> 2);
                    int g = (g5 << 3) | (g5 >> 2);
                    int b = (b5 << 3) | (b5 >> 2);
                    nearestLut[(r5 << 10) | (g5 << 5) | b5] = nearestIndex(r, g, b);
                }
            }
        }
    }

    /** 32x32x32 量化空间到调色板的 O(1) 映射 */
    private int[] mapToPalette(int[] argb) {
        int[] indices = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            int r5 = (p >> 19) & 0x1F;
            int g5 = (p >> 11) & 0x1F;
            int b5 = (p >> 3) & 0x1F;
            indices[i] = nearestLut[(r5 << 10) | (g5 << 5) | b5];
        }
        return indices;
    }

    private int nearestIndex(int r, int g, int b) {
        int best = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < 256; i++) {
            int pr = palette[i * 3] & 0xFF;
            int pg = palette[i * 3 + 1] & 0xFF;
            int pb = palette[i * 3 + 2] & 0xFF;
            int dr = pr - r, dg = pg - g, db = pb - b;
            int dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    /** NeuQuant 失败时的兜底：均匀 6x6x6 色立方 + 灰阶 */
    private byte[] uniformPalette() {
        byte[] tab = new byte[256 * 3];
        int idx = 0;
        for (int r = 0; r < 6; r++) {
            for (int g = 0; g < 6; g++) {
                for (int b = 0; b < 6; b++) {
                    tab[idx * 3] = (byte) (r * 51);
                    tab[idx * 3 + 1] = (byte) (g * 51);
                    tab[idx * 3 + 2] = (byte) (b * 51);
                    idx++;
                }
            }
        }
        while (idx < 256) {
            int v = (idx - 216) * 255 / 39;
            tab[idx * 3] = (byte) v;
            tab[idx * 3 + 1] = (byte) v;
            tab[idx * 3 + 2] = (byte) v;
            idx++;
        }
        return tab;
    }

    // ============================ LZW 编码 ============================

    /** GIF LZW 子块编码（经典 Weiner 实现，输入为调色板索引序列） */
    private static class LzwEncoder {
        private static final int EOF = -1;
        private static final int BITS = 12;
        private static final int HSIZE = 5003;

        private final int imgW;
        private final int imgH;
        private final int[] pixAry;
        private final int initCodeSize;

        private int remaining;
        private int curPixel;

        private static final int MAX_MAXCODE = 1 << BITS;
        private final int[] htab = new int[HSIZE];
        private final int[] codetab = new int[HSIZE];

        private int nBits;
        private int maxCode;
        private int freeEnt;
        private boolean clearFlg;

        private int gInitBits;
        private int clearCode;
        private int eofCode;

        private int curAccum;
        private int curBits;

        private final byte[] accum = new byte[256];
        private int aCount;

        private static final int[] MASKS = {
            0x0000, 0x0001, 0x0003, 0x0007, 0x000F, 0x001F, 0x003F, 0x007F,
            0x00FF, 0x01FF, 0x03FF, 0x07FF, 0x0FFF, 0x1FFF, 0x3FFF, 0x7FFF, 0xFFFF
        };

        LzwEncoder(int width, int height, int[] indices, int initCodeSize) {
            imgW = width;
            imgH = height;
            pixAry = indices;
            this.initCodeSize = Math.max(initCodeSize, 2);
        }

        void encode(OutputStream os) throws IOException {
            os.write(initCodeSize);
            remaining = imgW * imgH;
            curPixel = 0;
            compress(os);
        }

        private int maxCodeOf(int bits) {
            return (1 << bits) - 1;
        }

        private void compress(OutputStream outs) throws IOException {
            // GIF 规范：ClearCode = 2^minCodeSize = 256，解码器起始码宽 = minCodeSize+1 位
            gInitBits = initCodeSize + 1;
            clearFlg = false;
            nBits = gInitBits;
            maxCode = maxCodeOf(nBits);
            clearCode = 1 << initCodeSize;
            eofCode = clearCode + 1;
            freeEnt = clearCode + 2;

            curAccum = 0;
            curBits = 0;
            aCount = 0;

            int ent = nextPixel();

            int hshift = 0;
            for (int fcode = HSIZE; fcode < 65536; fcode *= 2) hshift++;
            hshift = 8 - hshift;

            for (int i = 0; i < HSIZE; i++) htab[i] = -1;
            output(outs, clearCode);

            outerLoop:
            while (true) {
                int c = nextPixel();
                if (c == EOF) break;
                int fcode = (c << BITS) + ent;
                int i = (c << hshift) ^ ent;
                if (htab[i] == fcode) {
                    ent = codetab[i];
                    continue;
                }
                if (htab[i] >= 0) {
                    int disp = HSIZE - i;
                    if (i == 0) disp = 1;
                    do {
                        if ((i -= disp) < 0) i += HSIZE;
                        if (htab[i] == fcode) {
                            ent = codetab[i];
                            continue outerLoop;
                        }
                    } while (htab[i] >= 0);
                }
                output(outs, ent);
                ent = c;
                if (freeEnt < MAX_MAXCODE) {
                    codetab[i] = freeEnt++;
                    htab[i] = fcode;
                } else {
                    clBlock(outs);
                }
            }
            output(outs, ent);
            output(outs, eofCode);
        }

        private int nextPixel() {
            if (remaining == 0) return EOF;
            remaining--;
            return pixAry[curPixel++];
        }

        private void clBlock(OutputStream outs) throws IOException {
            for (int i = 0; i < HSIZE; i++) htab[i] = -1;
            freeEnt = clearCode + 2;
            clearFlg = true;
            output(outs, clearCode);
        }

        private void output(OutputStream outs, int code) throws IOException {
            curAccum &= MASKS[curBits];
            if (curBits > 0) {
                curAccum |= (code << curBits);
            } else {
                curAccum = code;
            }
            curBits += nBits;
            while (curBits >= 8) {
                charOut((byte) (curAccum & 0xFF), outs);
                curAccum >>= 8;
                curBits -= 8;
            }
            if (freeEnt > maxCode || clearFlg) {
                if (clearFlg) {
                    nBits = gInitBits;
                    maxCode = maxCodeOf(nBits);
                    clearFlg = false;
                } else {
                    nBits++;
                    maxCode = (nBits == BITS) ? MAX_MAXCODE : maxCodeOf(nBits);
                }
            }
            if (code == eofCode) {
                while (curBits > 0) {
                    charOut((byte) (curAccum & 0xFF), outs);
                    curAccum >>= 8;
                    curBits -= 8;
                }
                flushChar(outs);
            }
        }

        private void charOut(byte c, OutputStream outs) throws IOException {
            accum[aCount++] = c;
            if (aCount >= 254) flushChar(outs);
        }

        private void flushChar(OutputStream outs) throws IOException {
            if (aCount > 0) {
                outs.write(aCount);
                outs.write(accum, 0, aCount);
                aCount = 0;
            }
        }
    }

    // ============================ NeuQuant 量化 ============================

    /**
     * Anthony Dekker 神经网络量化（256 色）。输入 ARGB int 像素。
     * 网络内部以 BGR 顺序存储以保持算法原样，palette() 输出转为 RGB。
     */
    private static class NeuQuant {
        private static final int NETSIZE = 256;
        private static final int PRIME1 = 499;
        private static final int PRIME2 = 491;
        private static final int PRIME3 = 487;
        private static final int PRIME4 = 503;
        private static final int MIN_PICTURE_BYTES = 3 * PRIME4;

        private static final int NET_BIASSHIFT = 4;
        private static final int NCYCLES = 100;
        private static final int INT_BIASSHIFT = 16;
        private static final int INT_BIAS = 1 << INT_BIASSHIFT;
        private static final int GAMMA_SHIFT = 10;
        private static final int GAMMA = 1 << GAMMA_SHIFT;
        private static final int BETA_SHIFT = 10;
        private static final int BETA = INT_BIAS >> BETA_SHIFT;
        private static final int BETA_GAMMA = INT_BIAS << (GAMMA_SHIFT - BETA_SHIFT);
        private static final int INIT_RAD = NETSIZE >> 3;
        private static final int RADIUS_BIASSHIFT = 6;
        private static final int RADIUS_BIAS = 1 << RADIUS_BIASSHIFT;
        private static final int INIT_RADIUS = INIT_RAD * RADIUS_BIAS;
        private static final int RADIUS_DEC = 30;
        private static final int ALPHA_BIASSHIFT = 10;
        private static final int INIT_ALPHA = 1 << ALPHA_BIASSHIFT;
        private static final int RAD_BIASSHIFT = 8;
        private static final int RAD_BIAS = 1 << RAD_BIASSHIFT;
        private static final int ALPHA_RAD_BIASSHIFT = ALPHA_BIASSHIFT + RAD_BIASSHIFT;
        private static final int ALPHA_RAD_BIAS = 1 << ALPHA_RAD_BIASSHIFT;

        private final int[] thePicture;
        private final int lengthCount;
        private final int sampleFac;

        private final int[][] network = new int[NETSIZE][3];
        private final int[] bias = new int[NETSIZE];
        private final int[] freq = new int[NETSIZE];
        private final int[] radpower = new int[INIT_RAD];

        NeuQuant(int[] pixels, int sampleFac) {
            thePicture = pixels;
            lengthCount = pixels.length;
            this.sampleFac = sampleFac;
            for (int i = 0; i < NETSIZE; i++) {
                int v = i;
                network[i][0] = v;
                network[i][1] = v;
                network[i][2] = v;
                freq[i] = INT_BIAS / NETSIZE;
                bias[i] = 0;
            }
        }

        /** 学习生成调色板，返回 RGB 三元组字节数组（256 项） */
        byte[] palette() {
            byte[] map = new byte[NETSIZE * 3];
            for (int i = 0; i < NETSIZE; i++) {
                map[i * 3] = clamp(network[i][2]);     // r
                map[i * 3 + 1] = clamp(network[i][1]); // g
                map[i * 3 + 2] = clamp(network[i][0]); // b
            }
            return map;
        }

        private static byte clamp(int v) {
            return (byte) (Math.max(0, Math.min(255, v)) & 0xFF);
        }

        void learn() {
            int samplefac = lengthCount < MIN_PICTURE_BYTES ? 1 : sampleFac;
            int alphadec = 30 + ((samplefac - 1) / 3);

            int delta = Math.max(1, lengthCount / NCYCLES);
            int alpha = INIT_ALPHA;
            int radius = INIT_RADIUS;
            int rad = radius >> RADIUS_BIASSHIFT;
            if (rad <= 1) rad = 0;
            for (int i = 0; i < rad; i++) {
                radpower[i] = alpha * (((rad * rad - i * i) * RAD_BIAS) / (rad * rad));
            }

            int step;
            if (lengthCount % PRIME1 != 0) step = PRIME1;
            else if (lengthCount % PRIME2 != 0) step = PRIME2;
            else if (lengthCount % PRIME3 != 0) step = PRIME3;
            else step = PRIME4;

            int samplepixels = lengthCount / samplefac;
            int p = 0;
            int i = 0;
            while (i < samplepixels) {
                int pixel = thePicture[p];
                int b = pixel & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int r = (pixel >> 16) & 0xFF;
                int j = contest(b, g, r);
                rad = radius >> RADIUS_BIASSHIFT;
                if (rad <= 1) rad = 0;
                alterNeighbour(alpha, rad, j, b, g, r);
                alterSingle(alpha, j, b, g, r);

                i++;
                if (i % delta == 0) {
                    alpha -= alpha / alphadec;
                    radius -= radius / RADIUS_DEC;
                    rad = radius >> RADIUS_BIASSHIFT;
                    if (rad <= 1) rad = 0;
                    for (j = 0; j < rad; j++) {
                        radpower[j] = alpha * (((rad * rad - j * j) * RAD_BIAS) / (rad * rad));
                    }
                }
                p += step;
                if (p >= lengthCount) p -= lengthCount;
            }
        }

        private int contest(int b, int g, int r) {
            int bestd = Integer.MAX_VALUE;
            int bestbiasd = bestd;
            int bestpos = -1;
            int bestbiaspos = bestpos;
            for (int i = 0; i < NETSIZE; i++) {
                int[] n = network[i];
                int dist = Math.abs(n[0] - b);
                dist += Math.abs(n[1] - g);
                dist += Math.abs(n[2] - r);
                if (dist < bestd) {
                    bestd = dist;
                    bestpos = i;
                }
                int biasdist = (dist << NET_BIASSHIFT) - bias[i];
                if (biasdist < bestbiasd) {
                    bestbiasd = biasdist;
                    bestbiaspos = i;
                }
                freq[i] -= BETA;
                bias[i] += BETA_GAMMA;
            }
            freq[bestpos] += BETA;
            bias[bestpos] -= BETA_GAMMA;
            return bestbiaspos;
        }

        private void alterSingle(int alpha, int i, int b, int g, int r) {
            int[] n = network[i];
            n[0] -= (alpha * (n[0] - b)) / INIT_ALPHA;
            n[1] -= (alpha * (n[1] - g)) / INIT_ALPHA;
            n[2] -= (alpha * (n[2] - r)) / INIT_ALPHA;
        }

        private void alterNeighbour(int alpha, int rad, int i, int b, int g, int r) {
            int lo = i - rad;
            if (lo < 0) lo = 0;
            int hi = i + rad;
            if (hi > NETSIZE) hi = NETSIZE;
            int j = i + 1;
            int k = i - 1;
            int m = 1;
            while ((j < hi) || (k > lo)) {
                int a = radpower[m++] / ALPHA_RAD_BIAS;
                if (j < hi) {
                    int[] n = network[j++];
                    n[0] -= (a * (n[0] - b));
                    n[1] -= (a * (n[1] - g));
                    n[2] -= (a * (n[2] - r));
                }
                if (k > lo) {
                    int[] n = network[k--];
                    n[0] -= (a * (n[0] - b));
                    n[1] -= (a * (n[1] - g));
                    n[2] -= (a * (n[2] - r));
                }
            }
        }
    }
}
