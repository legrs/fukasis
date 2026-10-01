// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
package com.example.ssa;
import androidx.activity.result.ActivityResultLauncher;
import android.widget.Toast;
import android.content.Intent;
import androidx.activity.result.contract.ActivityResultContracts;
import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Matrix;
import android.content.ContentUris;

import androidx.appcompat.app.AppCompatActivity;

import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.EditText;

import com.example.ssa.databinding.ActivityCsvBinding;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import android.content.ContentResolver;
import android.provider.MediaStore;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.IOException;

public class CsvActivity extends AppCompatActivity{

    private ImageView iv;
    private EditText path_et1; //et=EditText
    private EditText path_et2; //et=EditText

    private ActivityCsvBinding binding;
    private Activity activity = this;

    int[] pos = {0,0};
    float scale = 0.6F;
    float imgWidth ;
    float imgHeight ;
    float dispWidth ;
    float dispHeight;
    float fol;

    Uri uri4; // sensitivity curve

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityCsvBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        //setContentView(R.layout.activity_main);

        // UIs
        Button openBtn = binding.open;
        Button opencsvBtn = binding.opencsv;
        Button exportBtn = binding.export;
        SeekBar sb1 = binding.sb1;
        TextView t1 = binding.t1;
        FrameLayout line = binding.line;
        iv = binding.iv;
        iv.setScaleType(ImageView.ScaleType.MATRIX);

        
        path_et1 = binding.input1;
        path_et2 = binding.input2;
        openBtn.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                ContentResolver resolver = getContentResolver();
                Uri collection = MediaStore.Files.getContentUri("external");
                Uri uri = null;

                String filepath = "Documents/FUKASIS-app/imgs/" + path_et1.getText().toString() + "/";
                String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?";

                    //  jpg image ( for preview )
                
                String filename = "stacked.jpg";
                String[] selectionArgs = new String[]{filename, filepath};

                try(Cursor cursor = resolver.query(
                            collection,
                            new String[]{MediaStore.MediaColumns._ID},
                            selection,
                            selectionArgs,
                            null)){
                    if(cursor != null && cursor.moveToFirst()){
                        long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                        // exsists
                        uri = ContentUris.withAppendedId(collection, id);
                        Log.d("a","ありましたよっ！");
                    }else{
                        Log.d("a","な、ないです…");
                    }

                }
                if(uri != null){
                    iv.setImageURI(uri);
                    Log.d("a", "open");
                    Matrix matrix = new Matrix();
                    dispWidth = iv.getWidth();
                    dispHeight = iv.getHeight();
                    imgWidth = iv.getDrawable().getIntrinsicWidth();
                    imgHeight = iv.getDrawable().getIntrinsicHeight();
                    Log.d("a","" + dispWidth);
                    Log.d("a","" + dispHeight);
                    Log.d("a","" + imgWidth);
                    Log.d("a","" + imgHeight);
                    matrix.setScale(scale, scale);
                    //matrix.postTranslate(dispWidth - scale*imgWidth, -(imgHeight-dispHeight)/2);
                    matrix.postTranslate(dispWidth - scale*imgWidth, -(scale*imgHeight-dispHeight)/2);
                    iv.setImageMatrix(matrix);
                    iv.getLocationOnScreen(pos);
                }

            }
        });
        Button autoFolBtn = binding.autoFol;
        autoFolBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runAutoFol(autoFolBtn);
            }
        });
        // 前回使った感度データを自動で選んでおく
        Uri lastSensitivity = AutoCalibration.restoreSensitivity(this);
        if (lastSensitivity != null) {
            showSensitivity(lastSensitivity);
        }
        FloatingActionButton homeButton = binding.homeButton;
        homeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish(); // Finish the current activity and return to the previous one
            }
        });
        opencsvBtn.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                
                // MIMEタイプの設定（AndroidはCSVの判定が端末によってブレるため、少し広めに指定するのがコツです）
                intent.setType("*/*");
                String[] mimeTypes = {"text/csv", "text/comma-separated-values", "application/csv"};
                intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);

                // 用意しておいたランチャーを使って画面を起動
                csvPickerLauncher.launch(intent);
            }
        });
        exportBtn.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                ContentResolver resolver = getContentResolver();
                Uri collection = MediaStore.Files.getContentUri("external");
                Uri uri1 = null;
                Uri uri2 = null;
                Uri uri3 = null;
                Uri uri5 = null;

                String filepath = "Documents/FUKASIS-app/imgs/" + path_et1.getText().toString() + "/";
                String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
                    //  tiff image

                boolean isDarked = false;
                String filename = "darked.tif";
                String[] selectionArgs = {filename, filepath};
                try(Cursor cursor = resolver.query(
                            collection,
                            new String[]{MediaStore.MediaColumns._ID},
                            selection,
                            selectionArgs,
                            null)){
                    if(cursor != null && cursor.moveToFirst()){
                        long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                        // exsists
                        uri1 = ContentUris.withAppendedId(collection, id);
                        Log.d("a","ありましたよっ！");
                        isDarked = true;
                    }

                }
                if(!isDarked){
                    filename = "stacked.tif";
                    selectionArgs = new String[]{filename, filepath};
                    try(Cursor cursor = resolver.query(
                                collection,
                                new String[]{MediaStore.MediaColumns._ID},
                                selection,
                                selectionArgs,
                                null)){
                        if(cursor != null && cursor.moveToFirst()){
                            long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                            // exsists
                            uri1 = ContentUris.withAppendedId(collection, id);
                            Log.d("a","ありましたよっ！");
                        }else{
                            Log.d("a","stacked.tifもないですよ!！");
                        }

                    }
                }

                    // calibration data

                filepath = "Documents/FUKASIS-app/csv/calibdata/";
                filename = path_et2.getText().toString() + ".csv";
                selectionArgs = new String[]{filename, filepath};
                try(Cursor cursor = resolver.query(
                            collection,
                            new String[]{MediaStore.MediaColumns._ID},
                            selection,
                            selectionArgs,
                            null)){
                    if(cursor != null && cursor.moveToFirst()){
                        long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                        // exsists
                        uri2 = ContentUris.withAppendedId(collection, id);
                        Log.d("a","ありましたよっ！");
                    }else{
                        Log.d("a","(校正用ファイルが)ないです");
                    }

                }

                    // observation metadata

                filepath = "Documents/FUKASIS-app/imgs/" + path_et1.getText().toString() + "/";
                filename = "metadata.csv";
                selectionArgs = new String[]{filename, filepath};
                try(Cursor cursor = resolver.query(
                            collection,
                            new String[]{MediaStore.MediaColumns._ID},
                            selection,
                            selectionArgs,
                            null)){
                    if(cursor != null && cursor.moveToFirst()){
                        long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                        // exsists
                        uri3 = ContentUris.withAppendedId(collection, id);
                        Log.d("a","ありましたよっ！");
                    }else{
                        Log.d("a","(metadataが)ないです");
                    }

                }

                    // spectrum csv file

                ContentValues values = new ContentValues();
                uri5 = Cam.getUri(activity,"Documents/FUKASIS-app/csv/spectrum/", path_et1.getText().toString() + ".csv", "text/csv",resolver , values);


                try{
                    if(uri1 != null && uri2 != null && uri3 != null && uri4 != null && uri5 != null){
                        ParcelFileDescriptor pfd1 = resolver.openFileDescriptor(uri1, "r");
                        ParcelFileDescriptor pfd2 = resolver.openFileDescriptor(uri2, "r");
                        ParcelFileDescriptor pfd3 = resolver.openFileDescriptor(uri3, "r");
                        ParcelFileDescriptor pfd4 = resolver.openFileDescriptor(uri4, "r");
                        ParcelFileDescriptor pfd5 = resolver.openFileDescriptor(uri5, "w");

                        if(pfd1 != null && pfd2 != null && pfd3 != null && pfd4 != null && pfd5 != null){
                            Log.d("a",makecsv(pfd1.getFd(), pfd2.getFd(), pfd3.getFd(), pfd4.getFd(), pfd5.getFd(), (int)fol));

                            pfd1.close();
                            pfd2.close();
                            pfd3.close();
                            pfd4.close();
                            pfd5.close();

                            values.clear();
                            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                            resolver.update(uri5, values, null, null);

                            Log.d("a", "saved csv");

                        }

                    }
                }catch(IOException e){
                    e.printStackTrace();
                }
            }
        });
        sb1.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                Log.d("a","" + i);
                t1.setText("" + i);
                fol = imgWidth - i;
                line.setX(dispWidth+(-imgWidth + fol)*scale);
                line.setY(pos[1]-50);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });


    }
    @Override
    protected void onResume(){
        super.onResume();
        
    }
    @Override
    protected void onPause(){
        super.onPause();
    }

    // 0次光の位置を自動検出して sb1 に反映する. 画像の解析はバックグラウンドで行う
    private void runAutoFol(Button button) {
        String seq = path_et1.getText().toString().trim();
        if (seq.isEmpty()) {
            Toast.makeText(activity, "Sequence Nameを入力してください", Toast.LENGTH_SHORT).show();
            return;
        }
        if (imgWidth == 0) {
            // まだ画像を開いていなければ開くところから自動で行う
            binding.open.performClick();
            if (imgWidth == 0) {
                Toast.makeText(activity, seq + " の stacked.jpg が見つかりません", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        final boolean needCalibName = path_et2.getText().toString().trim().isEmpty();
        final int width = (int) imgWidth;
        final int progMin = binding.sb1.getMin();
        final int progMax = binding.sb1.getMax();
        ContentResolver resolver = getContentResolver();

        button.setEnabled(false);
        AutoCalibration.EXECUTOR.execute(() -> {
            String error = null;
            AutoCalibration.Analysis analysis = null;
            int progress = -1;
            // 校正データ名が空なら一番最近保存した校正データを使う
            final String calibName = needCalibName ? AutoCalibration.latestCalibrationName(resolver) : null;
            try {
                analysis = AutoCalibration.analyzeSequence(resolver, seq);
                progress = SpectrumCalibrator.detectFolProgress(analysis.image, width, progMin, progMax);
            } catch (SpectrumCalibrator.CalibrationException e) {
                error = e.getMessage();
            } catch (RuntimeException e) {
                Log.e("CsvAuto", "auto fol failed", e);
                error = String.valueOf(e.getMessage());
            }
            final String err = error;
            final AutoCalibration.Analysis a = analysis;
            final int p = progress;
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                button.setEnabled(true);
                if (err != null) {
                    Toast.makeText(activity, "自動検出に失敗しました: " + err, Toast.LENGTH_LONG).show();
                    return;
                }
                binding.sb1.setProgress(p);
                // setProgress は値が変わらないと listener を呼ばないので, fol と線の位置は明示的に反映する
                fol = imgWidth - p;
                binding.t1.setText("" + p);
                binding.line.setX(dispWidth+(-imgWidth + fol)*scale);
                binding.line.setY(pos[1]-50);
                StringBuilder message = new StringBuilder("0次光を検出しました (" + a.fileName + "): " + p);
                if (calibName != null && path_et2.getText().toString().trim().isEmpty()) {
                    path_et2.setText(calibName);
                    message.append("\n校正データ: ").append(calibName).append(" (最新)");
                }
                if (uri4 == null) {
                    message.append("\n感度データを OPEN SENSITIVITY DATA で選んでください");
                }
                String warning = SpectrumCalibrator.bandOffsetWarning(a.image);
                if (warning != null) {
                    message.append("\n注意: ").append(warning);
                }
                Toast.makeText(activity, message.toString(), Toast.LENGTH_LONG).show();
            });
        });
    }

    // 感度データを選択済みにして, ボタンにファイル名を出す
    private void showSensitivity(Uri uri) {
        uri4 = uri;
        binding.opencsv.setText("sensitivity: " + AutoCalibration.displayName(getContentResolver(), uri));
    }

    private final ActivityResultLauncher<Intent> csvPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        AutoCalibration.rememberSensitivity(this, uri);
                        showSensitivity(uri);
                    }
                }
            }
    );


    public native String makecsv(int fd1, int fd2, int fd3, int fd4, int fd5, int fol);
}
