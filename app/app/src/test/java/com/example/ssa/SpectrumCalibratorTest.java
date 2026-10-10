package com.example.ssa;

import org.junit.Test;

import static org.junit.Assert.*;

public class SpectrumCalibratorTest {

    // 合成データ: 幅 4000, 0次光はスライダー値 450 (x = 3550)
    private static final int WIDTH = 4000;
    private static final int FOL_PROGRESS = 450;
    private static final int FOL = WIDTH - FOL_PROGRESS;

    /** 合成の分散: 波長 = 400 + 0.3 * (距離 - 1400) */
    private static int distanceOf(double wavelength) {
        return (int) Math.round(1400 + (wavelength - 400) / 0.3);
    }

    /** 背景 + 0次光 + 指定波長の輝線 (+ Bayer の1画素ごとの段差) を持つ列プロファイル */
    private static double[] syntheticProfile(double[] wavelengths, double[] amplitudes) {
        double[] p = new double[WIDTH];
        for (int x = 0; x < WIDTH; x++) {
            p[x] = 30 + (x % 2 == 0 ? 3 : -3);
            p[x] += 5000 * Math.exp(-Math.pow(x - FOL, 2) / (2 * 4.0));
            for (int i = 0; i < wavelengths.length; i++) {
                int lx = FOL - distanceOf(wavelengths[i]);
                p[x] += amplitudes[i] * Math.exp(-Math.pow(x - lx, 2) / (2 * 9.0));
            }
        }
        return p;
    }

    private static SpectrumCalibrator.ImageProfile image(double[] profile) {
        return new SpectrumCalibrator.ImageProfile(profile.length, 1000, 500, profile);
    }

    @Test
    public void computeDeno_isCorrectForSimpleCase() {
        double[] tRef = {0, 1, 2, 3};
        double[] deno = SpectrumCalibrator.computeDeno(tRef);
        // deno[0] = (0-1)*(0-2)*(0-3)= -6
        assertEquals(-6.0, deno[0], 1e-9);
        // deno[1] = (1-0)*(1-2)*(1-3)= 2
        assertEquals(2.0, deno[1], 1e-9);
    }

    @Test
    public void lagrangeInterpolate_matchesLinear() {
        double[] tRef = {0, 10};
        double[] cRef = {400, 500};
        double[] deno = SpectrumCalibrator.computeDeno(tRef);
        double v = SpectrumCalibrator.lagrangeInterpolate(5, tRef, cRef, deno);
        assertEquals(450.0, v, 1e-6);
    }

    @Test
    public void detectPeaks_findsTwoPeaks() {
        double[] s = new double[100];
        s[20] = 100;
        s[21] = 10;
        s[70] = 80;
        // add baseline
        for (int i = 0; i < s.length; i++) {
            if (s[i] == 0) s[i] = 5;
        }
        s[19] = 10;
        s[69] = 10;
        s[71] = 10;
        int[] peaks = SpectrumCalibrator.detectPeaks(s, 0.2, 10);
        assertEquals(2, peaks.length);
        assertEquals(20, peaks[0]);
        assertEquals(70, peaks[1]);
    }

    @Test
    public void detectPeaks_returnsEmptyForFlat() {
        double[] s = new double[50];
        for (int i = 0; i < s.length; i++) s[i] = 10;
        int[] peaks = SpectrumCalibrator.detectPeaks(s, 0.2, 5);
        assertEquals(0, peaks.length);
    }

    @Test
    public void estimateFol_findsMaxInRightQuarter() {
        double[] col = new double[100];
        for (int i = 0; i < col.length; i++) col[i] = 10;
        col[90] = 100; // 0th order near right edge
        col[20] = 50; // fake peak on left should be ignored when searching right 25%
        int fol = SpectrumCalibrator.estimateFol(col, 0.25);
        assertEquals(90, fol);
    }

    @Test
    public void validateCalibration_detectsDuplicate() {
        double[] t = {100, 100, 200, 300};
        double[] c = {435.8, 546.1, 576.96, 611.6};
        assertFalse(SpectrumCalibrator.validateCalibration(t, c));
    }

    @Test
    public void validateCalibration_detectsNonAdjacentDuplicate() {
        double[] t = {100, 200, 100, 300};
        double[] c = {435.8, 546.1, 588.0, 611.6};
        assertFalse(SpectrumCalibrator.validateCalibration(t, c));
    }

    @Test
    public void validateCalibration_acceptsValid() {
        double[] t = {1800, 2100, 2400, 2700};
        double[] c = {435.8, 546.1, 576.96, 611.6};
        assertTrue(SpectrumCalibrator.validateCalibration(t, c));
    }

    @Test
    public void matchCatalog_picksCatalogLinesAmongBrighterDistractors() {
        double[] catalog = SpectrumCalibrator.DEFAULT_CATALOG;
        // 蛍光灯の他の輝線 (405.4, 487.7, 631.0) の方が明るくても, 間隔の比で正しい4本を選ぶ
        double[] wl = {405.4, 435.8, 487.7, 546.1, 588.0, 611.6, 631.0};
        double[] intens = {900, 100, 800, 120, 90, 110, 700};
        double[] d = new double[wl.length];
        for (int i = 0; i < wl.length; i++) d[i] = distanceOf(wl[i]);
        SpectrumCalibrator.CatalogMatch m = SpectrumCalibrator.matchCatalog(d, intens, catalog,
                SpectrumCalibrator.MIN_NM_PER_PX, SpectrumCalibrator.MAX_NM_PER_PX, SpectrumCalibrator.MAX_FIT_RMS_NM);
        assertNotNull(m);
        for (int i = 0; i < catalog.length; i++) {
            assertEquals(distanceOf(catalog[i]), m.distances[i], 1e-9);
        }
        assertEquals(0.3, m.nmPerPx, 0.01);
        assertTrue(m.rmsNm < 1.0);
    }

    @Test
    public void matchCatalog_keepsOrderOfUnsortedCatalog() {
        // EditText の波長が昇順でなくても, catalog[i] の距離が i 番目に入る (以前は逆順に割り当てていた)
        double[] catalog = {611.6, 435.8, 588.0, 546.1};
        double[] d = {distanceOf(435.8), distanceOf(546.1), distanceOf(588.0), distanceOf(611.6)};
        SpectrumCalibrator.CatalogMatch m = SpectrumCalibrator.matchCatalog(d, null, catalog,
                SpectrumCalibrator.MIN_NM_PER_PX, SpectrumCalibrator.MAX_NM_PER_PX, SpectrumCalibrator.MAX_FIT_RMS_NM);
        assertNotNull(m);
        assertEquals(distanceOf(611.6), m.distances[0], 1e-9);
        assertEquals(distanceOf(435.8), m.distances[1], 1e-9);
        assertEquals(distanceOf(588.0), m.distances[2], 1e-9);
        assertEquals(distanceOf(546.1), m.distances[3], 1e-9);
        // 長波長ほど 0次光から遠い
        assertTrue(m.distances[0] > m.distances[2]);
    }

    @Test
    public void matchCatalog_returnsNullWhenNotEnoughOrNoFit() {
        double[] catalog = SpectrumCalibrator.DEFAULT_CATALOG;
        double[] three = {1500, 1900, 2000};
        assertNull(SpectrumCalibrator.matchCatalog(three, null, catalog, 0.2, 0.45, 4));
        // 等間隔の4本はこのカタログの間隔の比と合わない
        double[] even = {1500, 1700, 1900, 2100};
        assertNull(SpectrumCalibrator.matchCatalog(even, null, catalog, 0.2, 0.45, 4));
    }

    @Test
    public void calibrate_findsFolAndCatalogLines() throws Exception {
        double[] catalog = SpectrumCalibrator.DEFAULT_CATALOG;
        double[] wl = {405.4, 435.8, 487.7, 546.1, 588.0, 611.6};
        double[] amp = {400, 300, 500, 600, 200, 900};
        SpectrumCalibrator.CalibrationResult r = SpectrumCalibrator.calibrate(
                image(syntheticProfile(wl, amp)), WIDTH, 350, 600, 1800, 2900, catalog);
        assertEquals(FOL_PROGRESS, r.folProgress);
        for (int i = 0; i < catalog.length; i++) {
            // スライダー値 = 0次光のスライダー値 + 距離
            assertEquals(FOL_PROGRESS + distanceOf(catalog[i]), r.peakProgress[i], 1);
            assertTrue(r.peakProgress[i] >= 1800 && r.peakProgress[i] <= 2900);
        }
    }

    @Test
    public void calibrate_failsWhenLinesCannotBeIdentified() {
        double[] wl = {500, 520};
        double[] amp = {500, 500};
        try {
            SpectrumCalibrator.calibrate(image(syntheticProfile(wl, amp)), WIDTH, 350, 600, 1800, 2900,
                    SpectrumCalibrator.DEFAULT_CATALOG);
            fail("expected CalibrationException");
        } catch (SpectrumCalibrator.CalibrationException expected) {
            // ok
        }
    }

    @Test
    public void detectFolProgress_rejectsWidthMismatchAndDarkImage() {
        double[] flat = new double[WIDTH];
        java.util.Arrays.fill(flat, 30);
        try {
            SpectrumCalibrator.detectFolProgress(image(flat), WIDTH, 350, 600);
            fail("expected CalibrationException for no 0th order");
        } catch (SpectrumCalibrator.CalibrationException expected) {
            // ok
        }
        try {
            SpectrumCalibrator.detectFolProgress(image(syntheticProfile(new double[0], new double[0])), WIDTH + 2, 350, 600);
            fail("expected CalibrationException for width mismatch");
        } catch (SpectrumCalibrator.CalibrationException expected) {
            // ok
        }
    }

    @Test
    public void detectFolProgress_searchesOnlySliderRange() throws Exception {
        double[] p = syntheticProfile(new double[0], new double[0]);
        // スライダーの範囲外 (右端) にもっと明るい点があっても無視する
        p[WIDTH - 10] = 1e6;
        assertEquals(FOL_PROGRESS, SpectrumCalibrator.detectFolProgress(image(p), WIDTH, 350, 600));
    }

    @Test
    public void findPeaksInWindow_ignoresZerothOrderOutsideWindow() {
        double[] p = syntheticProfile(new double[]{546.1}, new double[]{50});
        SpectrumCalibrator.Peaks peaks = SpectrumCalibrator.findPeaksInWindow(p, FOL, 1350, 2450);
        assertEquals(1, peaks.distances.length);
        assertEquals(distanceOf(546.1), peaks.distances[0], 1);
    }

    @Test
    public void bandOffsetWarning_onlyWhenBandLeavesCentralStrip() {
        double[] p = new double[10];
        // 高さ 1000 の中央は 500. 積算は 460..540
        assertNull(SpectrumCalibrator.bandOffsetWarning(new SpectrumCalibrator.ImageProfile(10, 1000, 520, p)));
        assertNull(SpectrumCalibrator.bandOffsetWarning(new SpectrumCalibrator.ImageProfile(10, 1000, -1, p)));
        String below = SpectrumCalibrator.bandOffsetWarning(new SpectrumCalibrator.ImageProfile(10, 1000, 560, p));
        assertNotNull(below);
        assertTrue(below.contains("下") && below.contains("60"));
        String above = SpectrumCalibrator.bandOffsetWarning(new SpectrumCalibrator.ImageProfile(10, 1000, 400, p));
        assertNotNull(above);
        assertTrue(above.contains("上") && above.contains("100"));
    }

    @Test
    public void bandOffsetWarning_usesProfileBand() {
        double[] p = new double[10];
        // 帯を 0.7 の位置に移した端末では, そこを基準にする
        assertNull(SpectrumCalibrator.bandOffsetWarning(new SpectrumCalibrator.ImageProfile(10, 1000, 700, p), 80, 0.7));
        assertNotNull(SpectrumCalibrator.bandOffsetWarning(new SpectrumCalibrator.ImageProfile(10, 1000, 500, p), 80, 0.7));
    }

    @Test
    public void calibrate_respectsNmPerPxRange() {
        double[] wl = {405.4, 435.8, 487.7, 546.1, 588.0, 611.6};
        double[] amp = {400, 300, 500, 600, 200, 900};
        // 合成データの分散は 0.3 nm/px. 許す範囲から外れていれば対応付けない
        try {
            SpectrumCalibrator.calibrate(image(syntheticProfile(wl, amp)), WIDTH, 350, 600, 1800, 2900,
                    SpectrumCalibrator.DEFAULT_CATALOG, 0.5, 1.0);
            fail("expected CalibrationException");
        } catch (SpectrumCalibrator.CalibrationException expected) {
            // ok
        }
    }

    @Test
    public void estimateGeometry_fromFluorescentLamp() throws Exception {
        double[] wl = {405.4, 435.8, 487.7, 546.1, 588.0, 611.6, 631.0};
        double[] amp = {400, 300, 500, 600, 200, 900, 300};
        SpectrumCalibrator.GeometryEstimate g = SpectrumCalibrator.estimateGeometry(
                image(syntheticProfile(wl, amp)), SpectrumCalibrator.DEFAULT_CATALOG, 400, 700);
        assertEquals(FOL, g.folX);
        assertEquals(FOL_PROGRESS, g.folProgress());
        assertEquals(0.3, g.match.nmPerPx, 0.005);
        assertEquals(1400, g.distanceAtMin, 5);
        assertEquals(2400, g.distanceAtMax, 5);
        assertEquals(1350, g.tRange()[0], 5);
        assertEquals(2450, g.tRange()[1], 5);
        assertArrayEquals(new int[]{300, 600}, g.folProgressRange());
        assertEquals(1700, g.peakProgressRange()[0], 5);
        assertEquals(3000, g.peakProgressRange()[1], 5);
        assertEquals(0.2, g.nmPerPxRange()[0], 0.01);
        assertEquals(0.45, g.nmPerPxRange()[1], 0.01);
        assertEquals(0.5, g.bandCenter, 1e-9);
        assertNull(g.warning());

        // 推定した範囲で, いつもの自動校正が通る (iPhone 版の SpectrumCalibratorTests と同じ)
        int[] fol = g.folProgressRange();
        int[] peak = g.peakProgressRange();
        double[] nm = g.nmPerPxRange();
        SpectrumCalibrator.CalibrationResult r = SpectrumCalibrator.calibrate(image(syntheticProfile(wl, amp)), WIDTH,
                fol[0], fol[1], peak[0], peak[1], SpectrumCalibrator.DEFAULT_CATALOG, nm[0], nm[1]);
        assertEquals(FOL_PROGRESS, r.folProgress);
    }

    @Test
    public void estimateGeometry_failsWithoutZerothOrder() {
        double[] flat = new double[WIDTH];
        java.util.Arrays.fill(flat, 30);
        try {
            SpectrumCalibrator.estimateGeometry(image(flat), SpectrumCalibrator.DEFAULT_CATALOG, 400, 700);
            fail("expected CalibrationException");
        } catch (SpectrumCalibrator.CalibrationException expected) {
            // ok
        }
    }

    @Test
    public void imageProfile_parsesNativeResult() {
        SpectrumCalibrator.ImageProfile img = SpectrumCalibrator.ImageProfile.fromNative(new double[]{3, 2, 1, 7, 8, 9});
        assertNotNull(img);
        assertEquals(3, img.width);
        assertEquals(2, img.height);
        assertEquals(1, img.bandCenterY);
        assertArrayEquals(new double[]{7, 8, 9}, img.profile, 0);
        assertNull(SpectrumCalibrator.ImageProfile.fromNative(null));
        assertNull(SpectrumCalibrator.ImageProfile.fromNative(new double[]{3, 2, 1, 7}));
    }
}
