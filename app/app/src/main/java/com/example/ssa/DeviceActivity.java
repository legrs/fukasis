// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
package com.example.ssa;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.example.ssa.databinding.ActivityDeviceBinding;

import org.json.JSONException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 端末設定. 他の機種で使うための準備をする画面.
 * <ul>
 *     <li>今使っているプロファイルとカメラの確認</li>
 *     <li>端末とカメラの情報を JSON で書き出す (プロファイルを作る材料. docs/porting.md 参照)</li>
 *     <li>蛍光灯の写真から 0次光の位置・分散・スペクトルの範囲を推定して, この端末のプロファイルにする</li>
 *     <li>プロファイルの JSON を読み込む / 同梱のプロファイルに戻す</li>
 * </ul>
 */
public class DeviceActivity extends AppCompatActivity {

    static final String DEVICE_DIR = "Documents/FUKASIS-app/device/";

    private ActivityDeviceBinding binding;
    private DeviceProfile draft;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDeviceBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        SystemBars.pad(binding.getRoot(), binding.getRoot(), SystemBars.ALL, true);

        binding.exportReport.setOnClickListener(v -> exportReport());
        binding.estimate.setOnClickListener(v -> estimate());
        binding.saveDraft.setOnClickListener(v -> saveDraft());
        binding.importProfile.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
            jsonPicker.launch(intent);
        });
        binding.reset.setOnClickListener(v -> {
            DeviceProfiles.clearOverride(this);
            Toast.makeText(this, "同梱のプロファイルに戻しました", Toast.LENGTH_SHORT).show();
            showInfo();
        });
        showInfo();
    }

    private void showInfo() {
        DeviceProfile p = DeviceProfiles.current(this);
        int[] fol = p.folProgress();
        int[] peak = p.peakProgress();
        double[] nm = p.nmPerPx();
        String text = String.format(Locale.US,
                "model      : %s %s\n"
                        + "profile    : %s (%s)%s%s\n"
                        + "camera     : %s  cfa %s\n"
                        + "spectrum   : t %d-%d px, band %d px @ %.3f\n"
                        + "sliders    : fol %d-%d, lines %d-%d\n"
                        + "nm/px      : %.3f-%.3f",
                Build.MANUFACTURER, Build.MODEL,
                p.id(), p.name(),
                DeviceProfiles.hasOverride(this) ? " [saved on this device]" : "",
                p.isGeneric() ? "\n             ※ 未登録の端末です. Galaxy S22 の値を使っています" : (p.verified() ? "" : " (unverified)"),
                DeviceProfiles.cameraId(this), DeviceProfile.cfaName(DeviceProfiles.cfa(this)),
                p.tMin(), p.tMax(), p.bandWidth(), p.bandCenter(),
                fol[0], fol[1], peak[0], peak[1], nm[0], nm[1]);
        binding.info.setText(text);
    }

    private String fileStem() {
        return Build.MODEL.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** text を Documents/FUKASIS-app/device/name に保存する. 成功したら true */
    private boolean writeDocument(String name, String mime, String text) {
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        Uri uri = Cam.getUri(this, DEVICE_DIR, name, mime, resolver, values);
        if (uri == null) {
            return false;
        }
        boolean saved = false;
        try (OutputStream out = resolver.openOutputStream(uri, "wt")) {
            if (out != null) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                saved = true;
            }
        } catch (IOException e) {
            Log.e("Device", "failed to write " + name, e);
        }
        Cam.finishOutput(resolver, uri, values, saved);
        return saved;
    }

    private void exportReport() {
        String name = fileStem() + "_report.json";
        String text;
        try {
            text = DeviceProfiles.report(this).toString(2);
        } catch (JSONException e) {
            Toast.makeText(this, "端末情報を作れません: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        boolean ok = writeDocument(name, "application/json", text);
        Toast.makeText(this, ok ? DEVICE_DIR + name + " に保存しました" : "保存に失敗しました", Toast.LENGTH_LONG).show();
    }

    // 蛍光灯の写真から, この端末のスペクトルの写り方を推定する (解析はバックグラウンドで)
    private void estimate() {
        String seq = binding.seqName.getText().toString().trim();
        if (seq.isEmpty()) {
            Toast.makeText(this, "Sequence Name を入力してください", Toast.LENGTH_SHORT).show();
            return;
        }
        DeviceProfile profile = DeviceProfiles.current(this);
        int cfa = DeviceProfiles.cfa(this);
        String cameraId = DeviceProfiles.cameraId(this);
        ContentResolver resolver = getContentResolver();
        binding.estimate.setEnabled(false);
        binding.result.setText("推定中…");
        AutoCalibration.EXECUTOR.execute(() -> {
            String error = null;
            DeviceProfile result = null;
            SpectrumCalibrator.GeometryEstimate g = null;
            try {
                AutoCalibration.Analysis a = AutoCalibration.analyzeSequence(resolver, seq, profile, cfa);
                // スペクトルの帯が積算する帯から外れていたら, 帯の写っている行で解析し直す
                if (a.image.bandCenterY >= 0
                        && SpectrumCalibrator.bandOffsetWarning(a.image, profile.bandWidth(), profile.bandCenter()) != null) {
                    a = AutoCalibration.analyzeSequence(resolver, seq, profile.bandWidth(),
                            (double) a.image.bandCenterY / a.image.height, cfa);
                }
                g = SpectrumCalibrator.estimateGeometry(a.image, SpectrumCalibrator.DEFAULT_CATALOG, 400, 700);
                String id = profile.isGeneric() ? fileStem().toLowerCase(Locale.ROOT) : profile.id();
                String name = profile.isGeneric() ? Build.MANUFACTURER + " " + Build.MODEL : profile.name();
                result = profile.withIdentity(id, name, Build.MODEL, cameraId, cfa).withGeometry(g);
            } catch (SpectrumCalibrator.CalibrationException e) {
                error = e.getMessage();
            } catch (RuntimeException e) {
                Log.e("Device", "estimate failed", e);
                error = String.valueOf(e.getMessage());
            }
            final String err = error;
            final DeviceProfile r = result;
            final SpectrumCalibrator.GeometryEstimate est = g;
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                binding.estimate.setEnabled(true);
                if (err != null) {
                    binding.result.setText("推定できませんでした: " + err);
                    binding.saveDraft.setEnabled(false);
                    return;
                }
                draft = r;
                int[] t = est.tRange();
                StringBuilder sb = new StringBuilder();
                sb.append(String.format(Locale.US,
                        "0次光      : x = %d (slider %d)\n分散       : %.3f nm/px (残差 %.2f nm, 輝線 %d 本)\n"
                                + "400-700nm  : 0次光から %.0f-%.0f px\n帯の位置   : %s\n",
                        est.folX, est.folProgress(), est.match.nmPerPx, est.match.rmsNm, est.peakCount,
                        est.distanceAtMin, est.distanceAtMax,
                        Double.isNaN(est.bandCenter) ? "不明" : String.format(Locale.US, "%.3f", est.bandCenter)));
                sb.append(String.format(Locale.US, "→ t %d-%d px で切り出します\n", t[0], t[1]));
                String warning = est.warning();
                if (warning != null) {
                    sb.append("注意: ").append(warning).append('\n');
                }
                sb.append('\n').append(r.toJsonString());
                binding.result.setText(sb.toString());
                binding.saveDraft.setEnabled(true);
            });
        });
    }

    private void saveDraft() {
        if (draft == null) {
            return;
        }
        try {
            DeviceProfiles.saveOverride(this, draft);
        } catch (IOException e) {
            Toast.makeText(this, "保存に失敗しました: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        // リポジトリの profiles/device_profiles.json に追加してもらえるよう, 共有できる場所にも書き出す
        String name = fileStem() + "_profile.json";
        boolean exported = writeDocument(name, "application/json", draft.toJsonString());
        Toast.makeText(this, "この端末のプロファイルにしました" + (exported ? " (" + DEVICE_DIR + name + ")" : ""),
                Toast.LENGTH_LONG).show();
        binding.saveDraft.setEnabled(false);
        showInfo();
    }

    private final ActivityResultLauncher<Intent> jsonPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null
                        || result.getData().getData() == null) {
                    return;
                }
                Uri uri = result.getData().getData();
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) {
                        throw new IOException("cannot open");
                    }
                    String text = DeviceProfiles.readAll(in);
                    DeviceProfile p;
                    try {
                        p = DeviceProfile.fromJsonString(text);
                    } catch (JSONException single) {
                        // device_profiles.json そのものを選んだ場合は, この端末に合うものを使う
                        p = DeviceProfile.match(DeviceProfile.parseCatalog(text), DeviceProfile.PLATFORM, Build.MODEL);
                        if (p == null) {
                            throw new JSONException("この端末 (" + Build.MODEL + ") 向けのプロファイルがありません");
                        }
                    }
                    DeviceProfiles.saveOverride(this, p);
                    Toast.makeText(this, p.id() + " を読み込みました", Toast.LENGTH_SHORT).show();
                    showInfo();
                } catch (IOException | JSONException e) {
                    Toast.makeText(this, "読み込めません: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
}
