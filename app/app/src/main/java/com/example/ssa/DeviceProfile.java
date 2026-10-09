// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
package com.example.ssa;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 端末ごとの設定 (リポジトリ直下の profiles/device_profiles.json. iPhone 版 DeviceProfile.swift と同じ形式).
 * <p>
 * 書かれていない項目は Galaxy S22 で使ってきた値になるので, 未登録の端末でも今まで通り動く.
 * Android に依存しない (org.json だけ) ので host の unit test で確かめられる.
 * 読み込みとカメラの自動判定は {@link DeviceProfiles} が行う.
 * </p>
 */
public final class DeviceProfile {

    public static final String PLATFORM = "android";

    // 既定値 (Galaxy S22)
    public static final int DEFAULT_T_MIN = 1800;
    public static final int DEFAULT_T_MAX = 2800;
    public static final int DEFAULT_BAND_WIDTH = 80;
    public static final double DEFAULT_BAND_CENTER = 0.5;
    public static final int[] DEFAULT_FOL_PROGRESS = {300, 600};
    public static final int[] DEFAULT_PEAK_PROGRESS = {1800, 2900};
    public static final double DEFAULT_KEYSTONE = 3.79668;
    public static final double DEFAULT_STRETCH = 1.05125;
    public static final double DEFAULT_FOCUS_ZOOM_CENTER_Y = 0.4;
    public static final double DEFAULT_GUIDE_LINE_Y = 2050;
    public static final double DEFAULT_GUIDE_LINE_Y_POINTING = 1800;

    // カラーフィルタ配列 (native の fk::Cfa / FK_CFA_* と同じ番号)
    public static final int CFA_RGGB = 0;
    public static final int CFA_GRBG = 1;
    public static final int CFA_GBRG = 2;
    public static final int CFA_BGGR = 3;
    public static final int CFA_MONO = 4;
    private static final String[] CFA_NAMES = {"RGGB", "GRBG", "GBRG", "BGGR", "MONO"};

    private final JSONObject json;

    private DeviceProfile(JSONObject json) {
        this.json = json;
    }

    /** JSON のプロファイル1つ. id は必須 */
    public static DeviceProfile fromJson(JSONObject o) throws JSONException {
        if (o.optString("id", "").isEmpty()) {
            throw new JSONException("profile needs \"id\"");
        }
        return new DeviceProfile(new JSONObject(o.toString()));
    }

    public static DeviceProfile fromJsonString(String text) throws JSONException {
        return fromJson(new JSONObject(text));
    }

    /** device_profiles.json の profiles 一覧 */
    public static List<DeviceProfile> parseCatalog(String text) throws JSONException {
        JSONArray arr = new JSONObject(text).getJSONArray("profiles");
        List<DeviceProfile> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            out.add(fromJson(arr.getJSONObject(i)));
        }
        return out;
    }

    /** 未登録の端末用. 中身は既定値 (Galaxy S22 と同じ値) だけ */
    public static DeviceProfile generic(String model) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", "generic");
            o.put("name", "未登録の端末 (" + model + ")");
            o.put("platform", PLATFORM);
            o.put("models", new JSONArray().put(model));
            return new DeviceProfile(o);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** この端末向けのプロファイル (機種名の先頭が一番長く一致したもの). なければ null */
    public static DeviceProfile match(List<DeviceProfile> profiles, String platform, String model) {
        DeviceProfile best = null;
        int bestLen = 0;
        for (DeviceProfile p : profiles) {
            int len = p.matchLength(platform, model);
            if (len > bestLen) {
                best = p;
                bestLen = len;
            }
        }
        return best;
    }

    /** 一致した機種名の長さ. 一致しなければ 0 */
    public int matchLength(String platform, String model) {
        String p = json.optString("platform", "");
        if (!p.isEmpty() && !p.equalsIgnoreCase(platform)) {
            return 0;
        }
        JSONArray models = json.optJSONArray("models");
        if (models == null || model == null) {
            return 0;
        }
        String m = model.toLowerCase(Locale.ROOT);
        int best = 0;
        for (int i = 0; i < models.length(); i++) {
            String prefix = models.optString(i, "").toLowerCase(Locale.ROOT);
            if (!prefix.isEmpty() && m.startsWith(prefix)) {
                best = Math.max(best, prefix.length());
            }
        }
        return best;
    }

    // ---- 値 (書かれていなければ既定値) ----

    public String id() {
        return json.optString("id");
    }

    public String name() {
        return json.optString("name", id());
    }

    public boolean isGeneric() {
        return "generic".equals(id());
    }

    public boolean verified() {
        return json.optBoolean("verified", false);
    }

    private JSONObject section(String name) {
        JSONObject o = json.optJSONObject(name);
        return o != null ? o : new JSONObject();
    }

    /** カメラ ID. 書かれていなければ null (自動で選ぶ) */
    public String cameraId() {
        String id = section("camera").optString("id", "");
        return id.isEmpty() ? null : id;
    }

    /** 書かれているカラーフィルタ配列 (CFA_*). 書かれていない/不明なら -1 */
    public int configuredCfa() {
        return cfaFromName(section("camera").optString("cfa", ""));
    }

    /** 使うカラーフィルタ配列. 書かれていなければカメラが報告する値, それも無ければ GBRG (今までの値) */
    public int cfa(int cameraReported) {
        int c = configuredCfa();
        if (c >= 0) {
            return c;
        }
        return cameraReported >= 0 ? cameraReported : CFA_GBRG;
    }

    public int tMin() {
        return section("spectrum").optInt("t_min", DEFAULT_T_MIN);
    }

    public int tMax() {
        return section("spectrum").optInt("t_max", DEFAULT_T_MAX);
    }

    public int bandWidth() {
        return section("spectrum").optInt("band_width", DEFAULT_BAND_WIDTH);
    }

    public double bandCenter() {
        return section("spectrum").optDouble("band_center", DEFAULT_BAND_CENTER);
    }

    public int[] folProgress() {
        return intRange(section("calibration").optJSONArray("fol_progress"), DEFAULT_FOL_PROGRESS);
    }

    public int[] peakProgress() {
        return intRange(section("calibration").optJSONArray("peak_progress"), DEFAULT_PEAK_PROGRESS);
    }

    /** 自動校正で許す分散 {min, max} (nm/pixel) */
    public double[] nmPerPx() {
        JSONArray a = section("calibration").optJSONArray("nm_per_px");
        if (a != null && a.length() == 2) {
            double lo = a.optDouble(0, Double.NaN);
            double hi = a.optDouble(1, Double.NaN);
            if (lo > 0 && lo < hi) {
                return new double[]{lo, hi};
            }
        }
        return new double[]{SpectrumCalibrator.MIN_NM_PER_PX, SpectrumCalibrator.MAX_NM_PER_PX};
    }

    private static int[] intRange(JSONArray a, int[] def) {
        if (a != null && a.length() == 2) {
            int lo = a.optInt(0, Integer.MIN_VALUE);
            int hi = a.optInt(1, Integer.MIN_VALUE);
            if (lo != Integer.MIN_VALUE && hi != Integer.MIN_VALUE && lo < hi) {
                return new int[]{lo, hi};
            }
        }
        return def.clone();
    }

    // 撮影画面のプレビュー
    public double keystone() {
        return section("preview").optDouble("keystone", DEFAULT_KEYSTONE);
    }

    public double stretch() {
        return section("preview").optDouble("stretch", DEFAULT_STRETCH);
    }

    public double focusZoomCenterY() {
        return section("preview").optDouble("focus_zoom_center_y", DEFAULT_FOCUS_ZOOM_CENTER_Y);
    }

    public double guideLineY() {
        return section("preview").optDouble("guide_line_y", DEFAULT_GUIDE_LINE_Y);
    }

    public double guideLineYPointing() {
        return section("preview").optDouble("guide_line_y_pointing", DEFAULT_GUIDE_LINE_Y_POINTING);
    }

    // ---- 変更 (新しいプロファイルを返す) ----

    /** 蛍光灯の写真から推定した値で, スペクトルの範囲とスライダーの範囲を置き換える */
    public DeviceProfile withGeometry(SpectrumCalibrator.GeometryEstimate g) {
        try {
            JSONObject o = new JSONObject(json.toString());
            JSONObject spectrum = o.optJSONObject("spectrum");
            if (spectrum == null) {
                spectrum = new JSONObject();
                o.put("spectrum", spectrum);
            }
            int[] t = g.tRange();
            spectrum.put("t_min", t[0]);
            spectrum.put("t_max", t[1]);
            if (!Double.isNaN(g.bandCenter)) {
                spectrum.put("band_center", g.bandCenter);
            }
            JSONObject calib = o.optJSONObject("calibration");
            if (calib == null) {
                calib = new JSONObject();
                o.put("calibration", calib);
            }
            int[] fol = g.folProgressRange();
            int[] peak = g.peakProgressRange();
            double[] nm = g.nmPerPxRange();
            calib.put("fol_progress", new JSONArray().put(fol[0]).put(fol[1]));
            calib.put("peak_progress", new JSONArray().put(peak[0]).put(peak[1]));
            calib.put("nm_per_px", new JSONArray().put(round3(nm[0])).put(round3(nm[1])));
            o.put("verified", false);
            return new DeviceProfile(o);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** id / 名前 / 機種名 / カメラを付け替える (この端末用の下書きを作るとき) */
    public DeviceProfile withIdentity(String id, String name, String model, String cameraId, int cfa) {
        try {
            JSONObject o = new JSONObject(json.toString());
            o.put("id", id);
            o.put("name", name);
            o.put("platform", PLATFORM);
            o.put("models", new JSONArray().put(model));
            JSONObject camera = o.optJSONObject("camera");
            if (camera == null) {
                camera = new JSONObject();
                o.put("camera", camera);
            }
            if (cameraId != null) {
                camera.put("id", cameraId);
            }
            if (cfa >= 0) {
                camera.put("cfa", cfaName(cfa));
            }
            return new DeviceProfile(o);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    public JSONObject toJson() {
        try {
            return new JSONObject(json.toString());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public String toJsonString() {
        try {
            return json.toString(2);
        } catch (JSONException e) {
            return json.toString();
        }
    }

    /** InputStream を全部読んで UTF-8 の文字列にする */
    public static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }

    // ---- カラーフィルタ配列 ----

    /** "RGGB" などを CFA_* に. 不明なら -1 */
    public static int cfaFromName(String name) {
        if (name == null) {
            return -1;
        }
        for (int i = 0; i < CFA_NAMES.length; i++) {
            if (CFA_NAMES[i].equalsIgnoreCase(name.trim())) {
                return i;
            }
        }
        return -1;
    }

    public static String cfaName(int cfa) {
        return cfa >= 0 && cfa < CFA_NAMES.length ? CFA_NAMES[cfa] : "?";
    }

    /**
     * CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT の値を CFA_* に.
     * RGGB..BGGR (0..3) は同じ番号. RGB(4) / MONO(5) / NIR(6) は Bayer ではないので MONO 扱い. 不明 (null) なら -1
     */
    public static int cfaFromAndroid(Integer arrangement) {
        if (arrangement == null) {
            return -1;
        }
        return arrangement >= 0 && arrangement <= 3 ? arrangement : CFA_MONO;
    }
}
