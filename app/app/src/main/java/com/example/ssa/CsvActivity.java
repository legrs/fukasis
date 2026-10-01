// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
package com.example.ssa;
import androidx.activity.result.ActivityResultLauncher;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.widget.Toast;

public class CsvActivity extends AppCompatActivity{

    // makecsv は libssa にある. 撮影画面(Cam)を経由せずに来ても読み込まれているようにする
    static {
        System.loadLibrary("ssa");
    }

    // 画像のデコードなど重い処理は UI スレッドの外で行う
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

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
                    // スライダーを動かさずに export しても現在の表示位置が使われるようにする
                    updateFol(sb1.getProgress());
                }

            }
        });
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
                String seq = path_et1.getText().toString().trim();
                String calibName = path_et2.getText().toString().trim();
                if (seq.isEmpty() || calibName.isEmpty()) {
                    Toast.makeText(activity, "Sequence Name と Calibration Data Name を入力してください", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (imgWidth == 0) {
                    Toast.makeText(activity, "先に open image してください", Toast.LENGTH_SHORT).show();
                    return;
                }
                ContentResolver resolver = getContentResolver();
                String imgPath = "Documents/FUKASIS-app/imgs/" + seq + "/";

                // tiff image (ダーク減算済みがあればそちらを使う)
                Uri uri1 = Cam.findUri(resolver, imgPath, "darked.tif");
                if (uri1 == null) {
                    uri1 = Cam.findUri(resolver, imgPath, "stacked.tif");
                }
                // calibration data
                Uri uri2 = Cam.findUri(resolver, "Documents/FUKASIS-app/csv/calibdata/", calibName + ".csv");
                // observation metadata
                Uri uri3 = Cam.findUri(resolver, imgPath, "metadata.csv");

                String missing = null;
                if (uri1 == null) missing = "darked.tif / stacked.tif";
                else if (uri2 == null) missing = "校正データ " + calibName + ".csv";
                else if (uri3 == null) missing = "metadata.csv";
                else if (uri4 == null) missing = "感度データ (OPEN SENSITIVITY DATA で選択してください)";
                if (missing != null) {
                    Toast.makeText(activity, missing + " が見つかりません", Toast.LENGTH_LONG).show();
                    return;
                }

                final Uri imgUri = uri1, calibUri = uri2, metaUri = uri3, sensitUri = uri4;
                final int folPx = (int) fol;
                exportBtn.setEnabled(false);
                executor.execute(() -> {
                    String err = exportSpectrum(resolver, seq, imgUri, calibUri, metaUri, sensitUri, folPx);
                    runOnUiThread(() -> {
                        exportBtn.setEnabled(true);
                        Toast.makeText(activity, err.isEmpty() ? "スペクトルを保存しました" : "失敗: " + err, Toast.LENGTH_LONG).show();
                    });
                });
            }
        });
        sb1.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                Log.d("a","" + i);
                updateFol(i);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });


    }
    private void updateFol(int i){
        binding.t1.setText("" + i);
        fol = imgWidth - i;
        binding.line.setX(dispWidth+(-imgWidth + fol)*scale);
        binding.line.setY(pos[1]-50);
    }

    // スペクトル CSV を書き出す. 成功時は空文字列, 失敗時はエラーメッセージ
    private String exportSpectrum(ContentResolver resolver, String seq, Uri imgUri, Uri calibUri, Uri metaUri, Uri sensitUri, int folPx) {
        ContentValues values = new ContentValues();
        Uri outUri = Cam.getUri(activity, "Documents/FUKASIS-app/csv/spectrum/", seq + ".csv", "text/csv", resolver, values);
        if (outUri == null) {
            return "保存先のファイルを作成できません";
        }
        String err;
        try (ParcelFileDescriptor pfd1 = resolver.openFileDescriptor(imgUri, "r");
             ParcelFileDescriptor pfd2 = resolver.openFileDescriptor(calibUri, "r");
             ParcelFileDescriptor pfd3 = resolver.openFileDescriptor(metaUri, "r");
             ParcelFileDescriptor pfd4 = resolver.openFileDescriptor(sensitUri, "r");
             ParcelFileDescriptor pfd5 = resolver.openFileDescriptor(outUri, "wt")) {
            if (pfd1 == null || pfd2 == null || pfd3 == null || pfd4 == null || pfd5 == null) {
                err = "ファイルを開けません";
            } else {
                err = makecsv(pfd1.getFd(), pfd2.getFd(), pfd3.getFd(), pfd4.getFd(), pfd5.getFd(), folPx);
            }
        } catch (IOException | RuntimeException e) {
            e.printStackTrace();
            err = "ファイルを開けません: " + e.getMessage();
        }
        Cam.finishOutput(resolver, outUri, values, err.isEmpty());
        Log.d("a", err.isEmpty() ? "saved csv" : err);
        return err;
    }

    @Override
    protected void onDestroy(){
        super.onDestroy();
        executor.shutdown();
    }

    @Override
    protected void onResume(){
        super.onResume();
        
    }
    @Override
    protected void onPause(){
        super.onPause();
    }
    private final ActivityResultLauncher<Intent> csvPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        uri4 = uri;
                    }
                }
            }
    );


    // 成功時は空文字列, 失敗時はエラーメッセージを返す
    public native String makecsv(int fd1, int fd2, int fd3, int fd4, int fd5, int fol);
}
