// SPDX-License-Identifier: MIT
package com.example.ssa;

import org.junit.Test;

import static org.junit.Assert.*;

public class CalibrationValidatorTest {

    // README の calibration 画面の例 (0次光 490, 輝線 2122, 2476, 2616, 2700) に相当する値
    private static final double[] T_REF = { 1632, 1986, 2126, 2210 };
    private static final double[] C_REF = { 435.8, 546.1, 588.0, 611.6 };
    // 0次光の位置 (画像の幅 4080 - 490)
    private static final int SIZE = 3590;

    // 従来の実装 (4 点を通る 3 次の Lagrange 補間)
    private static double lagrange(double[] tRef, double[] cRef, double t) {
        double wavelength = 0;
        for (int j = 0; j < 4; j++) {
            double nume = 1.0;
            double deno = 1.0;
            for (int k = 0; k < 4; k++) {
                if (k != j) {
                    nume *= (t - tRef[k]);
                    deno *= (tRef[j] - tRef[k]);
                }
            }
            wavelength += cRef[j] * nume / deno;
        }
        return wavelength;
    }

    @Test
    public void fourPointsGiveSameCurveAsLagrange() {
        CalibrationValidator.Poly f = CalibrationValidator.fit(T_REF, C_REF);
        assertNotNull(f);
        assertEquals(3, f.degree);
        for (int t = 1; t < SIZE; t++) {
            assertEquals(lagrange(T_REF, C_REF, t), f.at(t), 1e-6);
        }
    }

    @Test
    public void typicalCalibrationCoversWholeBand() {
        // 以前は 0次光から 1800 - 2800 px に固定で, この例では 488 nm からしか出力されなかった
        assertArrayEquals(new int[] { 1511, 2639, 0, 0 },
                CalibrationValidator.outputRange(CalibrationValidator.fit(T_REF, C_REF), T_REF, SIZE));
        CalibrationValidator.Result r = CalibrationValidator.validate(T_REF, C_REF, SIZE);
        assertEquals(CalibrationValidator.OK, r.status);
        assertEquals(400.15, r.wavelengthMin, 0.01);
        assertEquals(699.96, r.wavelengthMax, 0.01);
    }

    @Test
    public void outputNeverGoesBackwards() {
        // 校正点の外側で 3 次式が極値を持っても, 出力される範囲では波長が単調
        CalibrationValidator.Poly f = CalibrationValidator.fit(T_REF, C_REF);
        // この例の 3 次式は t = 2800 の少し先で折り返す
        assertTrue(f.at(3300) < f.at(2800));
        int[] range = CalibrationValidator.outputRange(f, T_REF, SIZE);
        for (int t = range[0] + 1; t <= range[1]; t++) {
            assertTrue(f.at(t - 1) < f.at(t));
        }
    }

    @Test
    public void imageEdgeIsNotTruncation() {
        // 画像の端で終わるのは折り返しではない
        CalibrationValidator.Result r = CalibrationValidator.validate(T_REF, C_REF, 2500);
        assertEquals(CalibrationValidator.OK, r.status);
        assertTrue(r.wavelengthMax < 690);
    }

    @Test
    public void swappedWavelengthsAreTruncated() {
        double[] t = { 1900, 2100, 2300, 2500 };
        double[] c = { 430, 550, 490, 610 };
        assertEquals(CalibrationValidator.TRUNCATED, CalibrationValidator.validate(t, c, SIZE).status);
    }

    @Test
    public void samePositionFallsBackToQuadratic() {
        // README にある「2次でよいなら同じ波長を2つのバーに同じ位置で」という使い方
        double[] t = { 1632, 1986, 1986, 2210 };
        double[] c = { 435.8, 546.1, 546.1, 611.6 };
        CalibrationValidator.Poly f = CalibrationValidator.fit(t, c);
        assertNotNull(f);
        assertEquals(2, f.degree);
        assertEquals(546.1, f.at(1986), 1e-6);
        assertEquals(CalibrationValidator.OK, CalibrationValidator.validate(t, c, SIZE).status);
    }

    @Test
    public void allSamePositionGivesNoOutput() {
        double[] t = { 2000, 2000, 2000, 2000 };
        double[] c = { 500, 500, 500, 500 };
        assertNull(CalibrationValidator.fit(t, c));
        assertEquals(CalibrationValidator.NO_OUTPUT, CalibrationValidator.validate(t, c, SIZE).status);
    }

    @Test
    public void wavelengthsOutOfBandGiveNoOutput() {
        double[] t = { 1900, 2100, 2300, 2500 };
        double[] c = { 1430, 1490, 1550, 1610 };
        assertEquals(CalibrationValidator.NO_OUTPUT, CalibrationValidator.validate(t, c, SIZE).status);
    }

    @Test
    public void sixPointsAreFittedByLeastSquares() {
        double[] t = { 1525, 1632, 1986, 2126, 2210, 2281 };
        double[] c = { 404.7, 435.8, 546.1, 588.0, 611.6, 631.1 };
        CalibrationValidator.Poly f = CalibrationValidator.fit(t, c);
        assertNotNull(f);
        assertEquals(3, f.degree);
        // 6 点すべては通らないが, どの点にも近い
        for (int i = 0; i < t.length; i++) {
            assertEquals(c[i], f.at(t[i]), 0.5);
        }
        assertArrayEquals(new int[] { 1509, 2615, 0, 0 }, CalibrationValidator.outputRange(f, t, SIZE));
    }
}
