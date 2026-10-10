// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
package com.example.ssa;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.widget.SeekBar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * この端末で使う {@link DeviceProfile} を決める.
 * <ol>
 *     <li>端末設定画面で保存した (または読み込んだ) プロファイル (アプリ内部の device_profile.json)</li>
 *     <li>アプリに同梱した profiles/device_profiles.json のうち, 機種名 (Build.MODEL) が一致するもの</li>
 *     <li>どれもなければ既定値 (Galaxy S22 と同じ値)</li>
 * </ol>
 * カメラ ID とカラーフィルタ配列は, プロファイルに書かれていなければカメラに問い合わせて決める.
 */
final class DeviceProfiles {

    private DeviceProfiles() {
    }

    static final String ASSET = "device_profiles.json";
    static final String OVERRIDE_FILE = "device_profile.json";
    private static final String TAG = "DeviceProfiles";

    private static DeviceProfile current;
    private static String cameraId;
    private static int cfa = -1;

    /** この端末のプロファイル */
    static synchronized DeviceProfile current(Context context) {
        if (current == null) {
            current = load(context.getApplicationContext());
        }
        return current;
    }

    /** 使うカメラの ID */
    static synchronized String cameraId(Context context) {
        resolveCamera(context);
        return cameraId;
    }

    /** 使うカラーフィルタ配列 (DeviceProfile.CFA_*) */
    static synchronized int cfa(Context context) {
        resolveCamera(context);
        return cfa;
    }

    /** 保存したプロファイルがあるか */
    static boolean hasOverride(Context context) {
        return overrideFile(context).exists();
    }

    /** この端末用のプロファイルとして保存し, 以後それを使う */
    static synchronized void saveOverride(Context context, DeviceProfile profile) throws IOException {
        File f = overrideFile(context);
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(profile.toJsonString().getBytes(StandardCharsets.UTF_8));
        }
        reset();
    }

    /** 保存したプロファイルを消して, 同梱のプロファイル (なければ既定値) に戻す */
    static synchronized void clearOverride(Context context) {
        File f = overrideFile(context);
        if (f.exists() && !f.delete()) {
            Log.e(TAG, "failed to delete " + f);
        }
        reset();
    }

    private static void reset() {
        current = null;
        cameraId = null;
        cfa = -1;
    }

    private static File overrideFile(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), OVERRIDE_FILE);
    }

    private static DeviceProfile load(Context context) {
        File f = overrideFile(context);
        if (f.exists()) {
            try (InputStream in = new FileInputStream(f)) {
                return DeviceProfile.fromJsonString(readAll(in));
            } catch (IOException | JSONException e) {
                Log.e(TAG, "ignore broken " + f, e);
            }
        }
        try (InputStream in = context.getAssets().open(ASSET)) {
            List<DeviceProfile> catalog = DeviceProfile.parseCatalog(readAll(in));
            DeviceProfile p = DeviceProfile.match(catalog, DeviceProfile.PLATFORM, Build.MODEL);
            if (p != null) {
                return p;
            }
        } catch (IOException | JSONException e) {
            Log.e(TAG, "failed to read " + ASSET, e);
        }
        return DeviceProfile.generic(Build.MODEL);
    }

    static String readAll(InputStream in) throws IOException {
        return DeviceProfile.readAll(in);
    }

    /**
     * SeekBar の範囲を range = {min, max} にする.
     * setMin は今の max を, setMax は今の min を超えられないので, 順番を選んで設定する
     */
    static void applyRange(SeekBar bar, int[] range) {
        if (range[0] > bar.getMax()) {
            bar.setMax(range[1]);
            bar.setMin(range[0]);
        } else {
            bar.setMin(range[0]);
            bar.setMax(range[1]);
        }
    }

    // ---- カメラ ----

    private static void resolveCamera(Context context) {
        if (cameraId != null) {
            return;
        }
        DeviceProfile p = current(context);
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        String id = p.cameraId();
        if (id == null) {
            id = chooseRawCamera(manager);
        }
        cameraId = id != null ? id : "0";
        int reported = -1;
        try {
            CameraCharacteristics c = manager.getCameraCharacteristics(cameraId);
            reported = DeviceProfile.cfaFromAndroid(c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT));
        } catch (CameraAccessException | IllegalArgumentException | NullPointerException e) {
            Log.e(TAG, "cannot read camera " + cameraId, e);
        }
        cfa = p.cfa(reported);
    }

    /** RAW で撮れる背面カメラ ("0" があればそれ, なければ RAW の画素数が一番多いもの). なければ null */
    static String chooseRawCamera(CameraManager manager) {
        String best = null;
        long bestPixels = -1;
        try {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Size raw = largestRaw(c);
                if (!isBack(c) || raw == null || !supportsRaw(c)) {
                    continue;
                }
                if ("0".equals(id)) {
                    return id;
                }
                long pixels = (long) raw.getWidth() * raw.getHeight();
                if (pixels > bestPixels) {
                    best = id;
                    bestPixels = pixels;
                }
            }
        } catch (CameraAccessException | RuntimeException e) {
            Log.e(TAG, "failed to list cameras", e);
        }
        return best;
    }

    private static boolean isBack(CameraCharacteristics c) {
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        return facing != null && facing == CameraMetadata.LENS_FACING_BACK;
    }

    private static boolean supportsRaw(CameraCharacteristics c) {
        int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (caps == null) {
            return false;
        }
        for (int cap : caps) {
            if (cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) {
                return true;
            }
        }
        return false;
    }

    private static boolean supportsManual(CameraCharacteristics c) {
        int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (caps == null) {
            return false;
        }
        for (int cap : caps) {
            if (cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) {
                return true;
            }
        }
        return false;
    }

    private static Size largestRaw(CameraCharacteristics c) {
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map != null ? map.getOutputSizes(ImageFormat.RAW_SENSOR) : null;
        if (sizes == null || sizes.length == 0) {
            return null;
        }
        Size best = sizes[0];
        for (Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) {
                best = s;
            }
        }
        return best;
    }

    /**
     * 端末とカメラの情報 (新しい端末のプロファイルを作るときの材料).
     * docs/porting.md にこの JSON の見方を書いてある.
     */
    static JSONObject report(Context context) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("manufacturer", Build.MANUFACTURER);
        o.put("model", Build.MODEL);
        o.put("device", Build.DEVICE);
        o.put("android_sdk", Build.VERSION.SDK_INT);
        o.put("selected_camera", cameraId(context));
        o.put("selected_cfa", DeviceProfile.cfaName(cfa(context)));
        o.put("profile", current(context).toJson());
        JSONArray cams = new JSONArray();
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String id : manager.getCameraIdList()) {
                cams.put(cameraReport(id, manager.getCameraCharacteristics(id)));
            }
        } catch (CameraAccessException | RuntimeException e) {
            o.put("camera_error", String.valueOf(e.getMessage()));
        }
        o.put("cameras", cams);
        return o;
    }

    private static JSONObject cameraReport(String id, CameraCharacteristics c) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        o.put("facing", facing == null ? "?" : facing == CameraMetadata.LENS_FACING_BACK ? "back"
                : facing == CameraMetadata.LENS_FACING_FRONT ? "front" : "external");
        o.put("raw", supportsRaw(c));
        o.put("manual_sensor", supportsManual(c));
        Size raw = largestRaw(c);
        if (raw != null) {
            o.put("raw_size", raw.getWidth() + "x" + raw.getHeight());
        }
        Integer arrangement = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        int cfaCode = DeviceProfile.cfaFromAndroid(arrangement);
        if (cfaCode >= 0) {
            o.put("cfa", DeviceProfile.cfaName(cfaCode));
        }
        Range<Integer> iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (iso != null) {
            o.put("iso_range", new JSONArray().put(iso.getLower()).put(iso.getUpper()));
        }
        Range<Long> expo = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (expo != null) {
            o.put("exposure_ms_range", new JSONArray().put(expo.getLower() / 1e6).put(expo.getUpper() / 1e6));
        }
        Integer whiteLevel = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        if (whiteLevel != null) {
            o.put("white_level", whiteLevel);
        }
        float[] focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (focal != null && focal.length > 0) {
            o.put("focal_length_mm", focal[0]);
        }
        Float minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        if (minFocus != null) {
            o.put("min_focus_diopter", minFocus);
        }
        SizeF physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        Size pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        if (physical != null && pixels != null && pixels.getWidth() > 0) {
            o.put("sensor_mm", physical.getWidth() + "x" + physical.getHeight());
            o.put("pixel_array", pixels.getWidth() + "x" + pixels.getHeight());
            // 画素ピッチ (μm). 回折格子の分散が何 pixel になるかの見積もりに使う
            o.put("pixel_pitch_um", Math.round(physical.getWidth() / pixels.getWidth() * 1e6) / 1000.0);
        }
        Rect active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (active != null) {
            o.put("active_array", active.width() + "x" + active.height());
        }
        Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        if (orientation != null) {
            o.put("sensor_orientation", orientation);
        }
        o.put("physical_cameras", new JSONArray(c.getPhysicalCameraIds()));
        return o;
    }
}
