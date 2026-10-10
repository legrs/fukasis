// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
package com.example.ssa;

import java.time.Instant;
import java.util.Locale;
import android.content.ContentUris;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.view.TextureView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.net.Uri;
import android.util.Log;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Build;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.example.ssa.databinding.ActivityCapBinding;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.content.ContentValues;
import android.content.ContentResolver;
import android.provider.MediaStore;
import java.io.OutputStream;
import android.database.Cursor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import androidx.core.content.ContextCompat;
import android.widget.ImageView;
import androidx.swiperefreshlayout.widget.CircularProgressDrawable;
import android.graphics.Color;

public class Cam {

    private static final String TAG = "Camera2Native";
    private CameraDevice camDev;
    private CaptureRequest.Builder capRequestBuilder;
    private CameraCaptureSession capSession;
    private CameraCharacteristics camCharacteristics;
    private CaptureRequest.Builder capSequenceBuilder;
    private SoundPool soundPool;
    int alarmSound;
    int shatterSound;
    private String camId = "0";
    private int expo, iso;
    private float fd;

    private int maxW, maxH;
    private float maxZoom; // 8.0

    // 端末ごとの設定 (プレビューの見せ方・カラーフィルタ配列)
    private DeviceProfile profile;
    private int cfa = DeviceProfile.CFA_GBRG;

    private Activity activity;

    // Used to load the 'ssa' library on application startup.
    static {
        System.loadLibrary("ssa");
    }

    private TextureView tv1;
    private TextureView tv2;
    private ImageView captureStatusIcon;
    private TextView indicator;
    private ImageReader rawImgReader;
    // background thread
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private boolean doPreview;
    private volatile boolean isCapturing = false;
    private volatile boolean isOpening = false;

    private boolean focus_lock = false;

    private int sequenceLength = 1;
    private String sequenceName = "test";
    private int currentCount = 0;

    // 白飛びチェック
    private int whiteLevel = 0; // 0 なら不明 (チェックしない)
    private volatile boolean checkOnly = false; // 試し撮り中 (保存もスタックもしない)
    private int saturatedFrames = 0; // 今の capture sequence で白飛びしていた枚数

    // constructor
    public Cam(Activity activity, String camId, SoundPool soundPool, int alarmSound, int shatterSound) {
        this.activity = activity;
        this.camId = camId;
        this.profile = DeviceProfiles.current(activity);
        this.cfa = DeviceProfiles.cfa(activity);
        this.soundPool = soundPool;
        this.alarmSound = alarmSound;
        this.shatterSound = shatterSound;
        this.doPreview = false;
    }

    public Cam(Activity activity, String camId, SoundPool soundPool, int alarmSound, int shatterSound, TextureView tv1,
            TextureView tv2) {
        this.activity = activity;
        this.camId = camId;
        this.profile = DeviceProfiles.current(activity);
        this.cfa = DeviceProfiles.cfa(activity);
        this.soundPool = soundPool;
        this.alarmSound = alarmSound;
        this.shatterSound = shatterSound;
        this.tv1 = tv1;
        this.tv2 = tv2;
        this.doPreview = true;
    }

    public Cam(Activity activity, String camId, SoundPool soundPool, int alarmSound, int shatterSound, TextureView tv1,
            TextureView tv2, ImageView captureStatusIcon) {
        this(activity, camId, soundPool, alarmSound, shatterSound, tv1, tv2);
        this.captureStatusIcon = captureStatusIcon;
    }

    // path(RELATIVE_PATH) 下の name というファイルを探す. 無ければ null
    public static Uri findUri(ContentResolver resolver, String path, String name) {
        Uri collection = MediaStore.Files.getContentUri("external");
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH
                + "=?";
        String[] selectionArgs = new String[] { name, path };

        try (Cursor cursor = resolver.query(
                collection,
                new String[] { MediaStore.MediaColumns._ID },
                selection,
                selectionArgs,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                // exists
                return ContentUris.withAppendedId(collection, id);
            }
        }
        return null;
    }

    // 書き込みが終わったファイルを確定する. 失敗していたら中途半端なファイルを残さないよう消す
    public static void finishOutput(ContentResolver resolver, Uri uri, ContentValues values, boolean succeeded) {
        if (succeeded) {
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        } else {
            resolver.delete(uri, null, null);
        }
    }

    // 既存ファイルを上書きする場合は "wt" で開くこと ("w" だと切り詰められず古い内容が末尾に残る)
    public static Uri getUri(Activity activity, String path, String name, String type, ContentResolver resolver,
            ContentValues values) {
        Uri uri = findUri(resolver, path, name);

        if (uri == null) {
            // does not exist
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, type);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, path);
            uri = activity.getContentResolver().insert(MediaStore.Files.getContentUri("external"), values);
        }
        return uri;
    }

    private void setup() {
        // onResume / onSurfaceTextureAvailable の両方から呼ばれるので二重に開かないようにする
        if (camDev != null || isOpening) {
            return;
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (backgroundHandler == null) {
            startBackgroundThread();
        }
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {

            camCharacteristics = manager.getCameraCharacteristics(camId);
            StreamConfigurationMap map = camCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Float maxDigitalZoom = camCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maxZoom = maxDigitalZoom != null ? maxDigitalZoom : 1.0f;
            Log.d("a", "maxzoom :" + maxZoom); // 8.0
            Size[] rawSizes = map != null ? map.getOutputSizes(ImageFormat.RAW_SENSOR) : null;
            if (rawSizes == null || rawSizes.length == 0) {
                postError("このカメラは RAW 撮影に対応していません");
                return;
            }
            Size largestRaw = rawSizes[0];
            for (Size s : rawSizes) {
                if (s.getWidth() * s.getHeight() > largestRaw.getWidth() * largestRaw.getHeight())
                    largestRaw = s;
            }
            maxW = largestRaw.getWidth();
            maxH = largestRaw.getHeight();
            Integer white = camCharacteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
            whiteLevel = (white != null) ? white : 0;
            rawImgReader = ImageReader.newInstance(maxW, maxH, ImageFormat.RAW_SENSOR, 2);
            rawImgReader.setOnImageAvailableListener(onRawImageAvailableListener, backgroundHandler);

            openCam();
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void setupCam() {
        if (tv1.isAvailable() && tv2.isAvailable()) {
            setup();
        } else {
            setTextureListener(tv1);
            setTextureListener(tv2);
        }
    }

    public void openCam() {
        Log.v("a", "opencam()");
        try {
            CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            isOpening = true;
            manager.openCamera(camId, stateCallback, backgroundHandler);
        } catch (SecurityException e) {
            isOpening = false;
            e.printStackTrace();
        } catch (Exception e) {
            isOpening = false;
            e.printStackTrace();
        }
    }

    public boolean isCapturing() {
        return isCapturing;
    }

    public void closeCam() {
        Log.v("a", "closeCam()");
        if (isCapturing) {
            // 撮影途中で画面を離れたらシーケンスは中断扱い
            isCapturing = false;
            postError("撮影が中断されました");
        }
        checkOnly = false;
        synchronized (this) {
            if (pendingImage != null) {
                pendingImage.close();
                pendingImage = null;
            }
            pendingResult = null;
        }
        if (capSession != null) {
            capSession.close();
            capSession = null;
        }
        if (camDev != null) {
            camDev.close();
            camDev = null;
        }
        if (rawImgReader != null) {
            rawImgReader.close();
            rawImgReader = null;
        }
    }

    public void createCamPreviewSession() {
        Log.v("a", "createCamPreviewSession()");

        Surface texSurface1 = new Surface(tv1.getSurfaceTexture());
        Surface texSurface2 = new Surface(tv2.getSurfaceTexture());
        Surface rawSurface = rawImgReader.getSurface();
        try {

            capRequestBuilder = camDev.createCaptureRequest(camDev.TEMPLATE_PREVIEW);
            capRequestBuilder.addTarget(texSurface1);
            capRequestBuilder.addTarget(texSurface2);

            // これをやるとcapture()した後連続で写真が保存されつづける
            // capRequestBuilder.addTarget(rawSurface);

            // preview
            camDev.createCaptureSession(Arrays.asList(texSurface1, texSurface2, rawSurface),
                    new CameraCaptureSession.StateCallback() {
                        // camDev.createCaptureSession(Arrays.asList(texSurface1), new
                        // CameraCaptureSession.StateCallback(){
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            capSession = session;
                            long maxExpo = camCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                                    .getUpper();
                            long minExpo = camCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                                    .getLower();
                            Log.d("a", "max expose time : " + maxExpo + "min expose time : " + minExpo);
                            capRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                            capRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 100000000L);
                            capRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, 3200);
                            capRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                            capRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, -1.0f);
                            // EIS
                            capRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
                            // OIS
                            capRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);

                            // preview
                            try {
                                capSession.setRepeatingRequest(capRequestBuilder.build(), null, backgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {

                        }
                    }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // カメラが受け付ける範囲に収めた ISO. カメラを開く前はそのまま返す
    public int clampIso(int iso) {
        Range<Integer> r = camCharacteristics != null
                ? camCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) : null;
        return r != null ? r.clamp(iso) : iso;
    }

    // カメラが受け付ける範囲に収めた露出時間 (ns). カメラを開く前はそのまま返す
    public long clampExposure(long ns) {
        Range<Long> r = camCharacteristics != null
                ? camCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) : null;
        return r != null ? r.clamp(ns) : ns;
    }

    // iso 50,64,80,100,
    // 125,160,200,250,
    // 320,400,500,640,
    // 800,1600,3200
    public void changeValueOfPreview(int iso, float fd, long expo, int zoom) {

        if (iso != 0) {
            capRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
        }
        if (fd != -100) {
            capRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            capRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, fd);
        }
        if (expo != 0) {
            capRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            capRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expo);
        }
        if (zoom != -1) {
            Rect sensorRect = camCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (zoom == 0) {
                transformTextures(0);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    capRequestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1.0f);
                }
                capRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, sensorRect);
            } else if (zoom == 1) {
                transformTextures(1);
                int cropW = sensorRect.width() / 4;
                // int cropH = cropW * maxH/maxW;
                int cropH = sensorRect.height() / 4;
                int cropX = (sensorRect.width() - cropW) / 2;
                int cropY = (int) ((float) profile.focusZoomCenterY() * (float) sensorRect.height() - (float) cropH / 2.0F);

                Log.d("a", String.format("%d,%d,%d,%d", cropX, cropY, cropX + cropW, cropY + cropH));

                Rect zoomRect = new Rect(cropX, cropY, cropX + cropW, cropY + cropH);

                // capRequestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 3.0f);
                // capRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                // CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
                capRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoomRect);
            }
        }

        // preview
        try {
            capSession.setRepeatingRequest(capRequestBuilder.build(), null, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // 撮影を開始できたら true. カメラの準備ができていなければ false
    public boolean startCaptureSession(long expo, int iso, float fd, int qty, String name, TextView indicator) {
        if (camDev == null || capSession == null || rawImgReader == null || isCapturing || checkOnly)
            return false;

        try {
            capSession.abortCaptures();
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        sequenceLength = qty;
        sequenceName = name;
        prepare(maxW, maxH);
        this.expo = (int) (expo / 1000000L);
        this.iso = iso;
        this.fd = fd;
        this.indicator = indicator;
        currentCount = 0;
        consecutiveFailures = 0;
        saturatedFrames = 0;
        Log.d("a", String.format("Capturing…\n%s, %d ms, %d, %f\n%d/%d done", name, expo, iso, fd, currentCount, qty));
        // indicator.setText(String.format("Capturing…\n%s, %d ms, %d, %f\n%d/%d
        // done",name,expo,iso,fd,currentCount,qty));

        try {
            capSequenceBuilder = camDev.createCaptureRequest(camDev.TEMPLATE_STILL_CAPTURE);
            capSequenceBuilder.addTarget(rawImgReader.getSurface());

            capSequenceBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            capSequenceBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expo);
            capSequenceBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            capSequenceBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            capSequenceBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, fd);

            // Log.v("a", String.format("start capture %d sec",(int)(expo/1000000L)));
        } catch (CameraAccessException e) {
            e.printStackTrace();
            return false;
        }

        isCapturing = true;
        capture();
        return true;
    }

    // UI に失敗を表示して CAPTURE ボタンを押せる状態に戻す
    private void postError(String message) {
        if (indicator == null) {
            Log.e("a", message);
            activity.runOnUiThread(() -> Toast.makeText(activity, message, Toast.LENGTH_LONG).show());
            return;
        }
        indicator.post(new Runnable() {
            @Override
            public void run() {
                setStatus(StatusType.ERROR, message, captureStatusIcon, indicator);
                enableButton(R.id.cap);
                enableButton(R.id.sat_check);
            }
        });
    }

    private void capture() {
        if (!isCapturing) {
            return;
        }
        if (camDev == null || capSession == null) {
            isCapturing = false;
            postError("カメラが閉じられたため撮影を中断しました");
            return;
        }
        if (sequenceLength <= currentCount) {

            // end of capture sequence
            isCapturing = false;
            soundPool.play(alarmSound, 1.0f, 1.0f, 0, 1, 1);
            // save csv & tiff
            // File file = new File(activity.getExternalFilesDir(null), "metadata.csv");
            ContentResolver resolver = activity.getContentResolver();

            ContentValues valuesCsv = new ContentValues();
            Uri uriCsv = getUri(activity, "Documents/FUKASIS-app/imgs/" + sequenceName + "/", "metadata.csv",
                    "text/csv", resolver, valuesCsv);

            // byte[] tiffBytes = processImg(file.getAbsolutePath());

            // if(tiffBytes == null || tiffBytes.length == 0){
            // Log.e("a", "failed to convert dng to tiff");
            // return ;
            // }
            // save tiff
            ContentValues valuesTiff = new ContentValues();
            ContentValues valuesPng = new ContentValues();
            Uri uriTiff = getUri(activity, "Documents/FUKASIS-app/imgs/" + sequenceName + "/", "stacked.tif",
                    "image/tiff", resolver, valuesTiff);
            Uri uriPng = getUri(activity, "Documents/FUKASIS-app/imgs/" + sequenceName + "/", "stacked.jpg",
                    "image/jpeg", resolver, valuesPng);

            // save tiff
            // ContentValues values = new ContentValues();
            // values.put(MediaStore.MediaColumns.DISPLAY_NAME, "stacked.tif");
            // values.put(MediaStore.MediaColumns.MIME_TYPE, "image/tiff");
            // values.put(MediaStore.MediaColumns.RELATIVE_PATH,
            // "Documents/FUKASIS-app/imgs/" + sequenceName);

            // Uri uri =
            // activity.getContentResolver().insert(MediaStore.Files.getContentUri("external"),
            // values);

            String saveError = null;
            if (uriCsv == null || uriTiff == null || uriPng == null) {
                saveError = "保存先のファイルを作成できません";
            } else {
                // csv(metadata)
                try (OutputStream output = activity.getContentResolver().openOutputStream(uriCsv, "wt")) {

                    // 後ろの cfa はスペクトル出力が別の端末で行われても Bayer 配列を正しく扱うため
                    String metadata = String.format(Locale.US, "%s, %s,  ISO %d, fd %f, %d msec * %d , cfa %s, device %s",
                            sequenceName, Instant.now().toString(), iso, fd, expo, sequenceLength,
                            DeviceProfile.cfaName(cfa), Build.MODEL);

                    output.write(metadata.getBytes("UTF-8"));
                    finishOutput(resolver, uriCsv, valuesCsv, true);

                    Log.d("a", "csv saved at " + uriCsv.toString());
                } catch (IOException e) {
                    e.printStackTrace();
                    finishOutput(resolver, uriCsv, valuesCsv, false);
                    saveError = "metadata.csv の保存に失敗しました";
                }
                // imgs
                String imgError;
                try (ParcelFileDescriptor pfdTiff = resolver.openFileDescriptor(uriTiff, "wt");
                        ParcelFileDescriptor pfdPng = resolver.openFileDescriptor(uriPng, "wt")) {
                    if (pfdTiff != null && pfdPng != null) {
                        imgError = saveImg(pfdTiff.getFd(), pfdPng.getFd(), cfa);
                    } else {
                        imgError = "画像ファイルを開けません";
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    imgError = "画像の保存に失敗しました: " + e.getMessage();
                }
                boolean imgSaved = imgError.isEmpty();
                finishOutput(resolver, uriTiff, valuesTiff, imgSaved);
                finishOutput(resolver, uriPng, valuesPng, imgSaved);
                if (!imgSaved) {
                    saveError = imgError;
                } else {
                    Log.d("a", "saved");
                }
            }
            if (saveError != null) {
                postError(saveError);
                return;
            }

            /*
             * // save csv
             * ContentValues values = new ContentValues();
             * values.put(MediaStore.MediaColumns.DISPLAY_NAME, "CSV_" +
             * System.currentTimeMillis() + ".csv");
             * values.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
             * values.put(MediaStore.MediaColumns.IS_PENDING, 1);
             * values.put(MediaStore.MediaColumns.RELATIVE_PATH,
             * "Documents/FUKASIS-app/imgs/" + sequenceName + "/");
             * 
             * resolver = activity.getContentResolver();
             * Uri uri = resolver.insert(MediaStore.Files.getContentUri("external"),
             * values);
             * // copy to mediastore
             * if(uri != null){
             * try(FileInputStream input = new FileInputStream(file)){
             * OutputStream output = activity.getContentResolver().openOutputStream(uri);
             * 
             * byte[] buff1 = new byte[1024 * 4];
             * int length;
             * while((length = input.read(buff1)) > 0){
             * output.write(buff1, 0, length);
             * }
             * 
             * values.clear();
             * values.put(MediaStore.MediaColumns.IS_PENDING, 0);
             * resolver.update(uri, values, null, null);
             * 
             * Log.d("a", "csv saved at "+uri.toString());
             * //String[] fileNames = getExternalFilesDir(null).list();
             * //for(int i=0; i<fileNames.length; i++){
             * // Log.d("a", fileNames[i]);
             * //}
             * }catch(IOException e){
             * e.printStackTrace();
             * resolver.delete(uri, null, null);
             * }
             * }
             */
            indicator.post(new Runnable() {
                @Override
                public void run() {
                    String message = withSaturationWarning(activity.getString(R.string.status_capture_completed,
                             sequenceName, expo, iso, fd, currentCount, sequenceLength));
                    setStatus(StatusType.SUCCESS, message, captureStatusIcon, indicator);

                    // Context 経由で Activity から CAPTURE ボタンを取得して透明度を戻す
                    if (indicator.getContext() instanceof Activity) {
                        Activity activity = (Activity) indicator.getContext();
                        Button capBtn = activity.findViewById(R.id.cap);

                        if (capBtn != null) {
                            capBtn.setAlpha(1.0f); // ボタンの透明度を元に戻す
                            capBtn.setEnabled(true); // もし無効化（Disabled）していた場合はタップ可能に戻す
                        }
                        enableButton(R.id.sat_check);
                    }
                }
            });
            Log.d("a", "capture sequence done !");
        } else {
            indicator.post(new Runnable() {
                @Override
                public void run() {
                    String message = withSaturationWarning(activity.getString(R.string.status_capturing, sequenceName, expo, iso, fd, currentCount, sequenceLength));
                    setStatus(StatusType.LOADING, message, captureStatusIcon, indicator);

                }
            });
            try {
                capSession.capture(capSequenceBuilder.build(), capCallback, backgroundHandler);
                Log.d("a", "start capture No." + currentCount);
            } catch (CameraAccessException | IllegalStateException e) {
                e.printStackTrace();
                isCapturing = false;
                postError("撮影に失敗しました: " + e.getMessage());
            }
        }
        return;
    }
    
    // 白飛びチェック: 今の設定で 1 枚だけ試し撮りし, 一次光領域の RAW 値を調べる (保存はしない)。
    // 試し撮りを始められたら true
    public boolean startSaturationCheck(long expo, int iso, float fd, TextView indicator) {
        if (camDev == null || capSession == null || rawImgReader == null || isCapturing || checkOnly) {
            return false;
        }
        this.indicator = indicator;
        try {
            capSession.abortCaptures();

            CaptureRequest.Builder builder = camDev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(rawImgReader.getSurface());
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expo);
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, fd);

            checkOnly = true;
            capSession.capture(builder.build(), checkCallback, backgroundHandler);
            return true;
        } catch (CameraAccessException | IllegalStateException e) {
            e.printStackTrace();
            checkOnly = false;
            return false;
        }
    }

    // 試し撮りの後始末。止まっているプレビューを再開し, 結果を表示してボタンを戻す
    private void finishSaturationCheck(final SaturationChecker.Result result) {
        checkOnly = false;
        try {
            if (capSession != null) {
                capSession.setRepeatingRequest(capRequestBuilder.build(), null, backgroundHandler);
            }
        } catch (CameraAccessException | IllegalStateException e) {
            e.printStackTrace();
        }
        indicator.post(new Runnable() {
            @Override
            public void run() {
                if (result == null) {
                    setStatus(StatusType.ERROR, activity.getString(R.string.error_saturation_check_failed),
                            captureStatusIcon, indicator);
                } else {
                    setStatus(result.anySaturated() ? StatusType.ERROR : StatusType.SUCCESS,
                            saturationMessage(result), captureStatusIcon, indicator);
                }
                enableButton(R.id.cap);
                enableButton(R.id.sat_check);
            }
        });
    }

    private final CameraCaptureSession.CaptureCallback checkCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request,
                @NonNull CaptureFailure failure) {
            if (checkOnly) {
                finishSaturationCheck(null);
            }
        }
    };

    // 一次光領域の白飛びを調べる。調べられなければ null
    private SaturationChecker.Result analyzeSaturation(Image img) {
        if (img == null || whiteLevel <= 0) {
            return null;
        }
        try {
            Image.Plane plane = img.getPlanes()[0];
            return SaturationChecker.analyze(plane.getBuffer(), plane.getRowStride(), img.getWidth(),
                    img.getHeight(), whiteLevel, cfa);
        } catch (RuntimeException e) {
            // チェックの失敗で撮影そのものを止めない
            e.printStackTrace();
            return null;
        }
    }

    private String saturationMessage(SaturationChecker.Result result) {
        String message = activity.getString(R.string.saturation_peaks,
                result.peakPercent(SaturationChecker.R),
                result.peakPercent(SaturationChecker.G),
                result.peakPercent(SaturationChecker.B));
        if (!result.anySaturated()) {
            return message + "\n" + activity.getString(R.string.saturation_ok);
        }
        String[] names = { "R", "G", "B" };
        StringBuilder channels = new StringBuilder();
        for (int ch = 0; ch < names.length; ch++) {
            if (result.isSaturated(ch)) {
                if (channels.length() > 0) {
                    channels.append(", ");
                }
                channels.append(names[ch]);
            }
        }
        return message + "\n" + activity.getString(R.string.saturation_warning, channels.toString());
    }

    // capture sequence 中に白飛びしたフレームがあれば, その枚数を status に書き足す
    private String withSaturationWarning(String message) {
        if (saturatedFrames > 0) {
            message += "\n" + activity.getString(R.string.saturation_frames_warning, saturatedFrames, currentCount);
        }
        return message;
    }

    private void enableButton(int id) {
        Button button = activity.findViewById(id);
        if (button != null) {
            button.setAlpha(1.0f);
            button.setEnabled(true);
        }
    }

    // ステータス用の enum
    public enum StatusType {
        SUCCESS,
        ERROR,
        LOADING
    }

    // $使用例： setStatus(StatusType.LOADING, message, captureStatusIcon, indicator);
    public static void setStatus(StatusType type, String message, ImageView captureStatusIcon,
            TextView messageIndicator) {
        // 1. テキストの更新
        if (messageIndicator != null) {
            messageIndicator.setText(message);
        }

        // 2. ガード節
        if (captureStatusIcon == null || type == null) {
            return;
        }

        // 3. タイプに応じた処理切り替え
        switch (type) {
            case SUCCESS:
                captureStatusIcon.setImageResource(R.drawable.ic_check);
                break;

            case ERROR:
                captureStatusIcon.setImageResource(R.drawable.ic_cross);
                break;

            case LOADING:
                CircularProgressDrawable progressDrawable = new CircularProgressDrawable(
                        captureStatusIcon.getContext());
                progressDrawable.setStyle(CircularProgressDrawable.DEFAULT);
                progressDrawable.setColorSchemeColors(Color.parseColor("#bbb7bd"));
                progressDrawable.start();

                captureStatusIcon.setImageDrawable(progressDrawable);
                break;
        }
    }

    // RAW 画像とそのキャプチャ結果(メタデータ)は別々のコールバックで, 順不同に届く.
    // SENSOR_TIMESTAMP が一致する組がそろってから処理する (どちらも backgroundHandler 上で呼ばれる)
    private Image pendingImage;
    private TotalCaptureResult pendingResult;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private int consecutiveFailures = 0;

    private final CameraCaptureSession.CaptureCallback capCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request,
                @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            synchronized (Cam.this) {
                pendingResult = result;
            }
            processCapturedFrame();
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request,
                @NonNull CaptureFailure failure) {
            super.onCaptureFailed(session, request, failure);
            Log.d("a", "capture failed: " + failure.getReason());
            synchronized (Cam.this) {
                if (pendingImage != null) {
                    pendingImage.close();
                    pendingImage = null;
                }
                pendingResult = null;
            }
            // 同じ番号をもう一度撮る
            if (backgroundHandler != null) {
                backgroundHandler.postDelayed(() -> capture(), 1000);
            }
        }
    };
    private final ImageReader.OnImageAvailableListener onRawImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) { // ?キャプチャ？
            Log.v("a", "img available");
            Image img = reader.acquireNextImage();
            if (checkOnly) {
                // 白飛びチェック用の試し撮り (保存もスタックもしない)
                SaturationChecker.Result result = analyzeSaturation(img);
                if (img != null) {
                    img.close();
                }
                finishSaturationCheck(result);
                return;
            }
            if (img == null) {
                return;
            }
            if (!isCapturing) {
                img.close();
                return;
            }
            synchronized (Cam.this) {
                if (pendingImage != null) {
                    pendingImage.close();
                }
                pendingImage = img;
            }
            processCapturedFrame();
        }
    };

    private void processCapturedFrame() {
        Image img;
        TotalCaptureResult result;
        synchronized (this) {
            if (pendingImage == null || pendingResult == null) {
                return;
            }
            Long resultTimestamp = pendingResult.get(CaptureResult.SENSOR_TIMESTAMP);
            if (resultTimestamp != null && resultTimestamp != pendingImage.getTimestamp()) {
                // 組にならないので古い方を捨てて, 新しい方の相方を待つ
                if (resultTimestamp < pendingImage.getTimestamp()) {
                    pendingResult = null;
                } else {
                    pendingImage.close();
                    pendingImage = null;
                }
                return;
            }
            img = pendingImage;
            result = pendingResult;
            pendingImage = null;
            pendingResult = null;
        }

        String accumulateError;
        boolean saturated = false;
        try {
            Log.d("a", "end capture No." + currentCount);
            // left vol. right vol. priority loop speed
            soundPool.play(shatterSound, 1.0f, 1.0f, 0, 0, 1);

            saveDNG(img, result);
            SaturationChecker.Result saturation = analyzeSaturation(img);
            saturated = saturation != null && saturation.anySaturated();
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buff = plane.getBuffer();

            // accumulate with OpenCVc++
            accumulateError = accumulateImg(buff, plane.getRowStride(), buff.remaining());
        } catch (RuntimeException e) {
            e.printStackTrace();
            accumulateError = String.valueOf(e.getMessage());
        } finally {
            img.close();
        }

        if (!accumulateError.isEmpty()) {
            // 積算できなかったフレームは枚数に数えず撮り直す. 続けて失敗するなら諦める
            Log.e("a", "accumulate failed: " + accumulateError);
            consecutiveFailures++;
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                isCapturing = false;
                postError("画像の積算に失敗しました: " + accumulateError);
                return;
            }
        } else {
            consecutiveFailures = 0;
            currentCount++;
            if (saturated) {
                saturatedFrames++;
            }
        }

        indicator.post(new Runnable() {
            @Override
            public void run() {
                String message = withSaturationWarning(activity.getString(R.string.status_capturing, sequenceName,
                        expo, iso, fd, currentCount, sequenceLength));
                setStatus(StatusType.LOADING, message, captureStatusIcon, indicator);
            }
        });

        if (backgroundHandler != null) {
            backgroundHandler.postDelayed(() -> capture(), 1000);
        }
    }

    private void saveDNG(Image img, TotalCaptureResult result) {
        ContentValues values = new ContentValues();
        ContentResolver resolver = activity.getContentResolver();
        Uri uri = getUri(activity, "Documents/FUKASIS-app/imgs/" + sequenceName + "/", currentCount + ".dng",
                "image/x-adobe-dng", resolver, values);
        DngCreator dngCreator = new DngCreator(camCharacteristics, result);
        // ContentResolver resolver = activity.getContentResolver();
        // Uri collection = MediaStore.Files.getContentUri("external");

        // Uri uri = null;
        // String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
        // String[] selectionArgs = new String[]{ currentCount + ".dng" };

        //// search whether same file exists.
        // try(Cursor c = resolver.query(collection,new
        //// String[]{MediaStore.MediaColumns._ID},selection,selectionArgs,null)){
        // if(c != null && c.moveToFirst()){
        // long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
        // // exists
        // uri = ContentUris.withAppendedId(collection, id);
        // }
        // }
        //

        // if(uri == null){
        // // does not exist
        // ContentValues values = new ContentValues();
        // values.put(MediaStore.MediaColumns.DISPLAY_NAME, currentCount + ".dng");
        // values.put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng");
        // values.put(MediaStore.MediaColumns.RELATIVE_PATH,
        // "Documents/FUKASIS-app/imgs/" + sequenceName);
        // uri =
        // activity.getContentResolver().insert(MediaStore.Files.getContentUri("external"),
        // values);
        // }

        try {
            if (uri != null) {
                try (OutputStream output = activity.getContentResolver().openOutputStream(uri, "wt")) {
                    dngCreator.writeImage(output, img);
                    Log.d(TAG, "DNG saved at " + uri.toString());

                    values.clear();
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    /*
                     * String[] fileNames = getExternalFilesDir(null).list();
                     * for(int i=0; i<fileNames.length; i++){
                     * Log.d("a", fileNames[i]);
                     * }
                     */
                }
            } else {
                Log.d("a", "uri is null!!!!!!!");
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            dngCreator.close();
        }

        /*
         * File file = new File(getExternalFilesDir(null), "IMG_" +
         * System.currentTimeMillis() + ".dng");
         * // ()のなかのfileoutputstreamは自動で閉じられる
         * try(FileOutputStream output = new FileOutputStream(file)){
         * dngCreator.writeImage(output, img);
         * Log.d(TAG, "DNG saved at " + file.getAbsolutePath());
         * 
         * String[] fileNames = getExternalFilesDir(null).list();
         * for(int i=0; i<fileNames.length; i++){
         * Log.d("a", fileNames[i]);
         * }
         * }catch(IOException e){
         * e.printStackTrace();
         * }finally{
         * dngCreator.close();
         * }
         */
    }

    public void setTextureListener(TextureView tv) {
        tv.setSurfaceTextureListener(textureListener);
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cam) {
            isOpening = false;
            camDev = cam;
            if (doPreview) {
                createCamPreviewSession();

            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cam) {
            isOpening = false;
            cam.close();
            if (camDev == cam) {
                camDev = null;
                capSession = null;
            }
        }

        @Override
        public void onError(@NonNull CameraDevice cam, int error) {
            isOpening = false;
            cam.close();
            if (camDev == cam) {
                camDev = null;
                capSession = null;
            }
            postError("カメラでエラーが発生しました (" + error + ")");
        }
    };
    private final TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull android.graphics.SurfaceTexture surface, int width, int height) {

            if (tv1.isAvailable() && tv2.isAvailable()) {
                setup();

                // max resolution
                surface.setDefaultBufferSize(maxW, maxH);
                Log.d("a", String.format("%d,%d", maxW, maxH));
                transformTextures(0);
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull android.graphics.SurfaceTexture surface, int width,
                int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull android.graphics.SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull android.graphics.SurfaceTexture surface) {

        }
    };

    public void startBackgroundThread() {
        if (backgroundThread != null) {
            return;
        }
        backgroundThread = new HandlerThread("Camerabackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    public void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void transformTextures(int zoom) {
        // 0:noZoom 1:focus 2:pointing
        Matrix matrix1 = new Matrix();
        Matrix matrix2 = new Matrix();

        // float centerX = viewWidth /2f;
        // float centerY = viewHeight /2f;
        //
        float s = 1f;
        if (zoom == 2) {
            s = 4f;
        }
        // プレビューの台形補正 (筐体の中のカメラの向きで決まるので端末ごとに違う)
        float ratio1 = (float) profile.keystone();
        float ratio2 = (float) profile.stretch();
        float ratio3 = 0.86667f;
        float w = tv1.getWidth();
        float h = tv1.getHeight();

        float[] src = {
                0, 0,
                w, 0,
                w, h,
                0, h
        };

        float h2 = ratio1 * ratio2 * w;
        float[] dst = {
                w / 2.0F - s * ratio1 * w / 2, 0,
                w / 2.0F + s * ratio1 * w / 2, 0,
                w / 2.0F + s * w / 2, s * h2,
                w / 2.0F - s * w / 2, s * h2
        };
        matrix1.setPolyToPoly(src, 0, dst, 0, 4);
        // matrix1.postTranslate(s*-(1-ratio3)*w/2, s*-(h2-h));
        matrix1.postTranslate(0, -(s * h2 - h));
        // matrix1.postTranslate(0, s*-h*0.9F);
        tv1.setTransform(matrix1);

        float s2 = 15;
        w = tv2.getWidth();
        h = tv2.getHeight();
        src = new float[] {
                0, 0,
                w, 0,
                w, h,
                0, h
        };
        if (zoom == 1) {
            // なぜかRectで移動したズームができないので，zoomingのときは一番うえまで使う
            h2 = h * 2;
            dst = new float[] {
                    w / 2.0f - s2 * w / 2, 0,
                    w / 2.0f + s2 * w / 2, 0,
                    w / 2.0f + s2 * w / 2, h2,
                    w / 2.0f - s2 * w / 2, h2
            };
            matrix2.setPolyToPoly(src, 0, dst, 0, 4);
            matrix2.postTranslate(0, 300);
        } else {
            h2 = h * 4;
            dst = new float[] {
                    w / 2.0f - s2 * w / 2, 0,
                    w / 2.0f + s2 * w / 2, 0,
                    w / 2.0f + s2 * w / 2, h2,
                    w / 2.0f - s2 * w / 2, h2
            };
            matrix2.setPolyToPoly(src, 0, dst, 0, 4);
            // matrix2.postTranslate(s*-(1-ratio3)*w/2 - s*w*(s2-1)/2, s*-(h2-h)*0.3f);
            matrix2.postTranslate(0, -(h2 - h) * 0.3f);
        }

        tv2.setTransform(matrix2);
    }

    /**
     * A native method that is implemented by the 'simple_spectroscope' native
     * library,
     * which is packaged with this application.
     */
    public native void prepare(int width, int height);

    public native String accumulateImg(ByteBuffer buff, int rowStride, int bufferSize);

    // public native byte[] processImg(String filepath);
    public native String saveImg(int fdTiff, int fdPng, int cfa);
}
