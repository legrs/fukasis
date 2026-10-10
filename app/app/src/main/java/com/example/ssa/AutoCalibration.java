// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
package com.example.ssa;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 自動校正で使う画像を MediaStore から探して解析する (Android 依存部分).
 * 判定ロジックは {@link SpectrumCalibrator} にある.
 */
final class AutoCalibration {

    private AutoCalibration() {
    }

    /** 画像の解析は重いので UI スレッドの外で, 1つずつ実行する */
    static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    /** 解析に使う画像. ダーク減算済みを優先し, 読めなければ stacked.tif を使う */
    static final String[] IMAGE_NAMES = {"darked.tif", "stacked.tif"};

    /** 解析した画像とそのファイル名 */
    static final class Analysis {
        final String fileName;
        final SpectrumCalibrator.ImageProfile image;

        Analysis(String fileName, SpectrumCalibrator.ImageProfile image) {
            this.fileName = fileName;
            this.image = image;
        }
    }

    static String imageDir(String seq) {
        return "Documents/FUKASIS-app/imgs/" + seq + "/";
    }

    static Uri find(ContentResolver resolver, String path, String name) {
        Uri collection = MediaStore.Files.getContentUri("external");
        String sel = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
        try (Cursor c = resolver.query(collection, new String[]{MediaStore.MediaColumns._ID}, sel,
                new String[]{name, path}, null)) {
            if (c != null && c.moveToFirst()) {
                long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                return ContentUris.withAppendedId(collection, id);
            }
        }
        return null;
    }

    static final String CALIBDATA_DIR = "Documents/FUKASIS-app/csv/calibdata/";

    /** 一番最近保存された校正データの名前 (拡張子なし). なければ null */
    static String latestCalibrationName(ContentResolver resolver) {
        Uri collection = MediaStore.Files.getContentUri("external");
        String sel = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
        try (Cursor c = resolver.query(collection, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, sel,
                new String[]{CALIBDATA_DIR, "%.csv"}, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                return name.substring(0, name.length() - ".csv".length());
            }
        } catch (RuntimeException e) {
            Log.e("AutoCalib", "failed to list calibration data", e);
        }
        return null;
    }

    // 感度データは端末内のどこにあるか分からないので, ユーザが選んだ URI を覚えておいて次回も使う
    private static final String PREFS = "auto_calibration";
    private static final String KEY_SENSITIVITY = "sensitivity_uri";

    /** 選ばれた感度データを覚える (アプリを再起動しても読めるよう永続的な読み取り権限をもらう) */
    static void rememberSensitivity(Context context, Uri uri) {
        try {
            context.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            // 永続化できない提供元のファイル. 今回だけ使う
            Log.d("AutoCalib", "cannot persist permission for " + uri);
            return;
        }
        prefs(context).edit().putString(KEY_SENSITIVITY, uri.toString()).apply();
    }

    /** 前回選んだ感度データ. まだ読めるものがなければ null */
    static Uri restoreSensitivity(Context context) {
        String saved = prefs(context).getString(KEY_SENSITIVITY, null);
        if (saved == null) {
            return null;
        }
        Uri uri = Uri.parse(saved);
        for (UriPermission p : context.getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(uri) && p.isReadPermission()) {
                return uri;
            }
        }
        return null;
    }

    /** 表示用のファイル名. 取れなければ URI の末尾 */
    static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null) {
                    return name;
                }
            }
        } catch (RuntimeException e) {
            Log.d("AutoCalib", "no display name for " + uri);
        }
        return uri.getLastPathSegment();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * seq の darked.tif → stacked.tif の順に, 解析できた最初の画像を返す.
     * 積算する帯とカラーフィルタ配列は端末プロファイルのものを使う (makecsv と同じ).
     * 空や壊れたファイル (失敗したダーク減算の残骸など) は飛ばして次の候補を使う.
     * 重いので UI スレッドから呼ばないこと.
     */
    static Analysis analyzeSequence(ContentResolver resolver, String seq, DeviceProfile profile, int cfa)
            throws SpectrumCalibrator.CalibrationException {
        return analyzeSequence(resolver, seq, profile.bandWidth(), profile.bandCenter(), cfa);
    }

    /** 積算する帯を指定して解析する (新しい端末のセットアップで, 帯の位置がまだ分からないとき) */
    static Analysis analyzeSequence(ContentResolver resolver, String seq, int bandWidth, double bandCenter, int cfa)
            throws SpectrumCalibrator.CalibrationException {
        boolean found = false;
        for (String name : IMAGE_NAMES) {
            Uri uri = find(resolver, imageDir(seq), name);
            if (uri == null) {
                continue;
            }
            found = true;
            try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r")) {
                if (pfd == null) {
                    continue;
                }
                SpectrumCalibrator.ImageProfile img =
                        SpectrumCalibrator.ImageProfile.fromNative(SpectrumCalibrator.analyzeImageNative(pfd.getFd(),
                                bandWidth, bandCenter, cfa));
                if (img != null) {
                    return new Analysis(name, img);
                }
                Log.d("AutoCalib", name + " を解析できませんでした");
            } catch (IOException | RuntimeException e) {
                Log.e("AutoCalib", "failed to open " + name, e);
            }
        }
        throw new SpectrumCalibrator.CalibrationException(found
                ? seq + " の darked.tif / stacked.tif を読み込めません"
                : seq + " に darked.tif / stacked.tif が見つかりません");
    }
}
