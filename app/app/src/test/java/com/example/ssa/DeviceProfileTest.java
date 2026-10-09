package com.example.ssa;

import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** profiles/device_profiles.json と DeviceProfile (iPhone 版 DeviceProfileTests.swift と同じ内容を確かめる) */
public class DeviceProfileTest {

    private static List<DeviceProfile> repositoryCatalog() throws Exception {
        // build.gradle.kts で profiles/ を test の resources にしている
        try (InputStream in = DeviceProfileTest.class.getResourceAsStream("/device_profiles.json")) {
            assertNotNull("profiles/device_profiles.json が見つかりません", in);
            return DeviceProfile.parseCatalog(DeviceProfile.readAll(in));
        }
    }

    @Test
    public void repositoryProfilesAreValid() throws Exception {
        List<DeviceProfile> catalog = repositoryCatalog();
        assertFalse(catalog.isEmpty());
        Set<String> ids = new HashSet<>();
        for (DeviceProfile p : catalog) {
            assertTrue("id は重複させない: " + p.id(), ids.add(p.id()));
            assertTrue(p.id() + ": t_min < t_max", p.tMin() < p.tMax());
            assertTrue(p.id() + ": band", p.bandWidth() > 0 && p.bandCenter() > 0 && p.bandCenter() < 1);
            JSONObject camera = p.toJson().optJSONObject("camera");
            if (camera != null && camera.has("cfa")) {
                assertTrue(p.id() + ": cfa", p.configuredCfa() >= 0);
            }
        }
    }

    @Test
    public void galaxyS22KeepsPreviousValues() throws Exception {
        DeviceProfile s22 = DeviceProfile.match(repositoryCatalog(), "android", "SM-S901Q");
        assertNotNull(s22);
        assertEquals("galaxy-s22", s22.id());
        assertEquals("0", s22.cameraId());
        // カメラが別の配列を報告しても, 今まで使ってきた GBRG を使う
        assertEquals(DeviceProfile.CFA_GBRG, s22.cfa(DeviceProfile.CFA_RGGB));
        assertEquals(1800, s22.tMin());
        assertEquals(2800, s22.tMax());
        assertEquals(80, s22.bandWidth());
        assertEquals(0.5, s22.bandCenter(), 0);
        assertArrayEquals(new int[]{1800, 2900}, s22.peakProgress());
        assertArrayEquals(new double[]{0.2, 0.45}, s22.nmPerPx(), 0);
        assertEquals(3.79668, s22.keystone(), 0);
        assertEquals(2050, s22.guideLineY(), 0);
        // docomo / au 版も
        assertEquals("galaxy-s22", DeviceProfile.match(repositoryCatalog(), "android", "SC-51C").id());
        assertEquals("galaxy-s22", DeviceProfile.match(repositoryCatalog(), "android", "SCG13").id());
    }

    @Test
    public void unknownDeviceFallsBackToDefaults() throws Exception {
        List<DeviceProfile> catalog = repositoryCatalog();
        assertNull(DeviceProfile.match(catalog, "android", "Pixel 8"));
        assertNull(DeviceProfile.match(catalog, "ios", "SM-S901Q")); // platform が違う
        DeviceProfile generic = DeviceProfile.generic("Pixel 8");
        assertTrue(generic.isGeneric());
        assertNull(generic.cameraId());
        assertEquals(DeviceProfile.CFA_RGGB, generic.cfa(DeviceProfile.CFA_RGGB)); // カメラの報告を使う
        assertEquals(DeviceProfile.CFA_GBRG, generic.cfa(-1)); // 報告がなければ今まで通り
        assertEquals(1800, generic.tMin());
        assertArrayEquals(new int[]{300, 600}, generic.folProgress());
    }

    @Test
    public void mostSpecificModelWins() throws Exception {
        List<DeviceProfile> catalog = DeviceProfile.parseCatalog("{\"profiles\": ["
                + "{\"id\": \"a\", \"models\": [\"SM-S90\"]},"
                + "{\"id\": \"b\", \"models\": [\"SM-S901\"], \"spectrum\": {\"t_min\": 1000, \"t_max\": 2000}}]}");
        assertEquals("b", DeviceProfile.match(catalog, "android", "sm-s901e").id());
        assertEquals("a", DeviceProfile.match(catalog, "android", "SM-S906").id());
        assertEquals(1000, DeviceProfile.match(catalog, "android", "SM-S901E").tMin());
    }

    @Test
    public void invalidRangesFallBackToDefaults() throws Exception {
        DeviceProfile p = DeviceProfile.fromJsonString("{\"id\": \"x\", \"calibration\": "
                + "{\"fol_progress\": [600, 300], \"peak_progress\": [1], \"nm_per_px\": [0.5, 0.1]}}");
        assertArrayEquals(DeviceProfile.DEFAULT_FOL_PROGRESS, p.folProgress());
        assertArrayEquals(DeviceProfile.DEFAULT_PEAK_PROGRESS, p.peakProgress());
        assertArrayEquals(new double[]{SpectrumCalibrator.MIN_NM_PER_PX, SpectrumCalibrator.MAX_NM_PER_PX}, p.nmPerPx(), 0);
        try {
            DeviceProfile.fromJsonString("{\"name\": \"no id\"}");
            fail("expected JSONException");
        } catch (org.json.JSONException expected) {
            // ok
        }
    }

    @Test
    public void cfaNames() {
        assertEquals(DeviceProfile.CFA_GBRG, DeviceProfile.cfaFromName("gbrg"));
        assertEquals(-1, DeviceProfile.cfaFromName("xyz"));
        assertEquals("BGGR", DeviceProfile.cfaName(DeviceProfile.CFA_BGGR));
        // CameraCharacteristics の値: RGGB..BGGR はそのまま, RGB / MONO / NIR は MONO
        assertEquals(DeviceProfile.CFA_GRBG, DeviceProfile.cfaFromAndroid(1));
        assertEquals(DeviceProfile.CFA_MONO, DeviceProfile.cfaFromAndroid(5));
        assertEquals(-1, DeviceProfile.cfaFromAndroid(null));
    }

    @Test
    public void withGeometryAndIdentity() throws Exception {
        double[] wl = {405.4, 435.8, 487.7, 546.1, 588.0, 611.6, 631.0};
        // SpectrumCalibratorTest と同じ合成データ (幅 4000, 0次光 x = 3550, 0.3 nm/px)
        double[] profile = new double[4000];
        for (int x = 0; x < profile.length; x++) {
            profile[x] = 30 + 5000 * Math.exp(-Math.pow(x - 3550, 2) / 8.0);
            for (double l : wl) {
                int lx = 3550 - (int) Math.round(1400 + (l - 400) / 0.3);
                profile[x] += 500 * Math.exp(-Math.pow(x - lx, 2) / 18.0);
            }
        }
        SpectrumCalibrator.GeometryEstimate g = SpectrumCalibrator.estimateGeometry(
                new SpectrumCalibrator.ImageProfile(4000, 3000, 1410, profile), SpectrumCalibrator.DEFAULT_CATALOG, 400, 700);
        DeviceProfile p = DeviceProfile.generic("Pixel 8")
                .withIdentity("pixel-8", "Google Pixel 8", "Pixel 8", "0", DeviceProfile.CFA_RGGB)
                .withGeometry(g);
        assertEquals("pixel-8", p.id());
        assertFalse(p.isGeneric());
        assertFalse(p.verified());
        assertEquals("0", p.cameraId());
        assertEquals(DeviceProfile.CFA_RGGB, p.configuredCfa());
        assertArrayEquals(g.tRange(), new int[]{p.tMin(), p.tMax()});
        assertArrayEquals(g.folProgressRange(), p.folProgress());
        assertArrayEquals(g.peakProgressRange(), p.peakProgress());
        assertEquals(0.47, p.bandCenter(), 1e-9);
        assertEquals(1, p.matchLength("android", "Pixel 8") > 0 ? 1 : 0);

        // JSON にして読み直しても同じ
        DeviceProfile back = DeviceProfile.fromJsonString(p.toJsonString());
        assertEquals(p.tMin(), back.tMin());
        assertArrayEquals(p.nmPerPx(), back.nmPerPx(), 0);
    }
}
