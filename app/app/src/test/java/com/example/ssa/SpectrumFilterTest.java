// SPDX-License-Identifier: MIT
package com.example.ssa;

import org.junit.Test;

import static org.junit.Assert.*;

public class SpectrumFilterTest {

    private static final int N = 200;

    private static float[] wavelengths() {
        float[] x = new float[N];
        for (int i = 0; i < N; i++) {
            x[i] = 400 + 0.3f * i;
        }
        return x;
    }

    // なだらかな連続光に輝線を 2 本載せたもの
    private static float[] intensities() {
        float[] y = new float[N];
        for (int i = 0; i < N; i++) {
            y[i] = (float) (0.1 + 0.05 * Math.sin(i / 20.0));
        }
        y[50] = 0.6f;
        y[51] = 1.0f;
        y[52] = 0.6f;
        y[120] = 0.3f;
        y[121] = 0.9f;
        y[122] = 0.3f;
        return y;
    }

    private static float max(float[] values) {
        float m = Float.NEGATIVE_INFINITY;
        for (float v : values) {
            m = Math.max(m, v);
        }
        return m;
    }

    private static void assertAscending(float[] values) {
        for (int i = 1; i < values.length; i++) {
            assertTrue(values[i - 1] <= values[i]);
        }
    }

    @Test
    public void emissionLinesAreKept() {
        SpectrumFilter.Result r = SpectrumFilter.filter(wavelengths(), intensities(), true);
        assertEquals(0, r.excluded);
        assertEquals(N, r.wavelength.length);
        assertEquals(1.0f, max(r.intensity), 0f);
    }

    @Test
    public void obviousOutliersAreExcluded() {
        float[] y = intensities();
        y[80] = 50.0f; // 孤立したスパイク
        y[150] = 30.0f; // 2 点続くスパイク
        y[151] = 28.0f;
        y[10] = -3.0f; // 負の強度
        y[20] = Float.NaN;

        SpectrumFilter.Result on = SpectrumFilter.filter(wavelengths(), y, true);
        assertEquals(5, on.excluded);
        assertEquals(N - 5, on.intensity.length);
        // 輝線は残る
        assertEquals(1.0f, max(on.intensity), 0f);

        // 切替をオフにしても NaN だけは描けないので外す
        SpectrumFilter.Result off = SpectrumFilter.filter(wavelengths(), y, false);
        assertEquals(1, off.excluded);
        assertEquals(50.0f, max(off.intensity), 0f);
    }

    @Test
    public void foldedWavelengthsAreExcluded() {
        // 校正がずれて, 波長が途中から戻ってしまった csv
        int fold = 30;
        float[] x = new float[N + fold];
        float[] y = new float[N + fold];
        System.arraycopy(wavelengths(), 0, x, 0, N);
        System.arraycopy(intensities(), 0, y, 0, N);
        for (int i = 0; i < fold; i++) {
            x[N + i] = x[N - 1] - 0.3f * (i + 1);
            y[N + i] = 0.1f;
        }

        SpectrumFilter.Result on = SpectrumFilter.filter(x, y, true);
        assertEquals(fold, on.excluded);
        assertAscending(on.wavelength);

        // オフでも波長の昇順には並べ替える (並んでいないとグラフが描けない)
        SpectrumFilter.Result off = SpectrumFilter.filter(x, y, false);
        assertEquals(0, off.excluded);
        assertEquals(N + fold, off.wavelength.length);
        assertAscending(off.wavelength);
    }

    @Test
    public void descendingFileIsSorted() {
        float[] x = wavelengths();
        float[] y = intensities();
        float[] xr = new float[N];
        float[] yr = new float[N];
        for (int i = 0; i < N; i++) {
            xr[i] = x[N - 1 - i];
            yr[i] = y[N - 1 - i];
        }
        SpectrumFilter.Result r = SpectrumFilter.filter(xr, yr, true);
        assertEquals(0, r.excluded);
        assertAscending(r.wavelength);
        assertEquals(400f, r.wavelength[0], 0f);
    }

    @Test
    public void emptyInput() {
        SpectrumFilter.Result r = SpectrumFilter.filter(new float[0], new float[0], true);
        assertEquals(0, r.excluded);
        assertEquals(0, r.wavelength.length);
    }

    @Test
    public void parseValue() {
        assertEquals(1.5f, SpectrumFilter.parseValue(" 1.5 "), 0f);
        assertEquals(-2e-3f, SpectrumFilter.parseValue("-2e-3"), 0f);
        assertTrue(Float.isNaN(SpectrumFilter.parseValue("nan")));
        assertTrue(Float.isNaN(SpectrumFilter.parseValue("-nan")));
        assertEquals(Float.POSITIVE_INFINITY, SpectrumFilter.parseValue("inf"), 0f);
        assertEquals(Float.NEGATIVE_INFINITY, SpectrumFilter.parseValue("-inf"), 0f);
    }

    @Test(expected = NumberFormatException.class)
    public void parseValueRejectsHeader() {
        SpectrumFilter.parseValue("wavelength/nm");
    }
}
