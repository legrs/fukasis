package com.example.ssa;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpectrumCalibratorTest {

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
    public void validateCalibration_acceptsValid() {
        double[] t = {1800, 2100, 2400, 2700};
        double[] c = {435.8, 546.1, 576.96, 611.6};
        assertTrue(SpectrumCalibrator.validateCalibration(t, c));
    }

    @Test
    public void selectBestPeaks_trimsByIntensity() {
        int[] peaks = {10, 20, 30, 40, 50, 60};
        double[] intens = {10, 100, 20, 90, 30, 80};
        int[] sel = SpectrumCalibrator.selectBestPeaks(peaks, intens, SpectrumCalibrator.DEFAULT_CATALOG, 4);
        assertEquals(4, sel.length);
        // should contain the 4 highest intensity peaks: indices 1,3,5,4? Actually sorted by x: 20,40,50,60
        // intensities: 100,90,80,30 => peaks 20,40,60,50 sorted => 20,40,50,60
        assertEquals(20, sel[0]);
        assertEquals(40, sel[1]);
        assertEquals(50, sel[2]);
        assertEquals(60, sel[3]);
    }
}
