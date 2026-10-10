// SPDX-License-Identifier: MIT
package com.example.ssa;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.*;

public class SaturationCheckerTest {

    private static final int WIDTH = 16;
    private static final int HEIGHT = 8;
    // 行末に余白がある場合も確かめる
    private static final int ROW_STRIDE = WIDTH * 2 + 6;
    private static final int WHITE = 1023;

    private static ByteBuffer newImage(int fill) {
        ByteBuffer buff = ByteBuffer.allocate(ROW_STRIDE * HEIGHT).order(ByteOrder.nativeOrder());
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                put(buff, x, y, fill);
            }
        }
        return buff;
    }

    private static void put(ByteBuffer buff, int x, int y, int val) {
        buff.putShort(y * ROW_STRIDE + x * 2, (short) val);
    }

    private static SaturationChecker.Result analyze(ByteBuffer buff, int cfa) {
        return SaturationChecker.analyze(buff, ROW_STRIDE, WHITE, cfa, 0, 0, WIDTH, HEIGHT);
    }

    @Test
    public void noSaturation() {
        SaturationChecker.Result r = analyze(newImage(100), SaturationChecker.CFA_GBRG);
        assertFalse(r.anySaturated());
        assertEquals(10, r.peakPercent(SaturationChecker.R));
        assertEquals(10, r.peakPercent(SaturationChecker.G));
        assertEquals(10, r.peakPercent(SaturationChecker.B));
        // G は R, B の 2 倍ある
        assertEquals(WIDTH * HEIGHT / 4, r.pixels[SaturationChecker.R]);
        assertEquals(WIDTH * HEIGHT / 2, r.pixels[SaturationChecker.G]);
        assertEquals(WIDTH * HEIGHT / 4, r.pixels[SaturationChecker.B]);
    }

    @Test
    public void onlyBlueSaturated_gbrg() {
        ByteBuffer buff = newImage(100);
        // GBRG では (x 奇数, y 偶数) が B
        for (int x = 1; x < 11; x += 2) {
            put(buff, x, 2, WHITE);
        }
        SaturationChecker.Result r = analyze(buff, SaturationChecker.CFA_GBRG);
        assertTrue(r.isSaturated(SaturationChecker.B));
        assertFalse(r.isSaturated(SaturationChecker.G));
        assertFalse(r.isSaturated(SaturationChecker.R));
        assertTrue(r.anySaturated());
        assertEquals(5, r.saturatedPixels[SaturationChecker.B]);
        assertEquals(100, r.peakPercent(SaturationChecker.B));
    }

    @Test
    public void channelFollowsColorFilterArrangement() {
        ByteBuffer buff = newImage(100);
        for (int x = 0; x < 10; x += 2) {
            put(buff, x, 0, WHITE);
        }
        // (偶数, 偶数) は RGGB なら R, BGGR なら B, GBRG と GRBG なら G
        assertTrue(analyze(buff, SaturationChecker.CFA_RGGB).isSaturated(SaturationChecker.R));
        assertTrue(analyze(buff, SaturationChecker.CFA_BGGR).isSaturated(SaturationChecker.B));
        assertTrue(analyze(buff, SaturationChecker.CFA_GBRG).isSaturated(SaturationChecker.G));
        assertTrue(analyze(buff, SaturationChecker.CFA_GRBG).isSaturated(SaturationChecker.G));
        // 不明な配列は GBRG として扱う
        assertTrue(analyze(buff, 99).isSaturated(SaturationChecker.G));
    }

    @Test
    public void hotPixelIsNotSaturation() {
        ByteBuffer buff = newImage(100);
        put(buff, 0, 1, WHITE);
        SaturationChecker.Result r = analyze(buff, SaturationChecker.CFA_GBRG);
        assertFalse(r.anySaturated());
        assertEquals(100, r.peakPercent(SaturationChecker.R));
    }

    @Test
    public void regionOutsideIsIgnored() {
        ByteBuffer buff = newImage(100);
        for (int x = 0; x < WIDTH; x++) {
            put(buff, x, 0, WHITE);
        }
        SaturationChecker.Result r = SaturationChecker.analyze(buff, ROW_STRIDE, WHITE,
                SaturationChecker.CFA_GBRG, 0, 2, WIDTH, HEIGHT);
        assertFalse(r.anySaturated());
    }

    @Test
    public void bufferStateIsNotChanged() {
        ByteBuffer buff = newImage(100);
        buff.position(10);
        ByteOrder order = buff.order();
        analyze(buff, SaturationChecker.CFA_GBRG);
        assertEquals(10, buff.position());
        assertEquals(order, buff.order());
    }

    @Test
    public void firstOrderRegion() {
        assertArrayEquals(new int[] { 730, 1490, 2380, 1570 }, SaturationChecker.firstOrderRegion(4080, 3060));
        // 想定より小さいセンサでは横方向は全域
        assertArrayEquals(new int[] { 0, 200, 640, 280 }, SaturationChecker.firstOrderRegion(640, 480));
    }
}
