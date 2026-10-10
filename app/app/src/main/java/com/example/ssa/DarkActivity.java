// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
package com.example.ssa;
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

import com.example.ssa.databinding.ActivityDarkBinding;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import android.content.ContentResolver;
import android.provider.MediaStore;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.widget.Toast;

public class DarkActivity extends AppCompatActivity{

    // processImgs は libssa にある. 撮影画面(Cam)を経由せずに来ても読み込まれているようにする
    static {
        System.loadLibrary("ssa");
    }

    // 画像のデコードなど重い処理は UI スレッドの外で行う
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private EditText path_et1; //et=EditText
    private EditText path_et2; //et=EditText

    private ActivityDarkBinding binding;
    private Activity activity = this;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityDarkBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        SystemBars.pad(binding.getRoot());

        //setContentView(R.layout.activity_main);

        // UIs
        Button exportBtn = binding.export;
        
        path_et1 = binding.input1;
        path_et2 = binding.input2;
        FloatingActionButton homeButton = binding.homeButton;
        homeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish(); // Finish the current activity and return to the previous one
            }
        });
        exportBtn.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                String lightName = path_et1.getText().toString().trim();
                String darkName = path_et2.getText().toString().trim();
                if (lightName.isEmpty() || darkName.isEmpty()) {
                    Toast.makeText(activity, "観測とダークフレームの名前を入力してください", Toast.LENGTH_SHORT).show();
                    return;
                }
                ContentResolver resolver = getContentResolver();
                Uri uri1 = Cam.findUri(resolver, "Documents/FUKASIS-app/imgs/" + lightName + "/", "stacked.tif");
                Uri uri2 = Cam.findUri(resolver, "Documents/FUKASIS-app/imgs/" + darkName + "/", "stacked.tif");
                // 入力がそろっていないのに空の darked.tif を作ると, csv 画面がそれを読んでしまう
                if (uri1 == null) {
                    Toast.makeText(activity, lightName + "/stacked.tif が見つかりません", Toast.LENGTH_LONG).show();
                    return;
                }
                if (uri2 == null) {
                    Toast.makeText(activity, darkName + "/stacked.tif が見つかりません", Toast.LENGTH_LONG).show();
                    return;
                }

                exportBtn.setEnabled(false);
                executor.execute(() -> {
                    String err = subtractDark(resolver, lightName, uri1, uri2);
                    runOnUiThread(() -> {
                        exportBtn.setEnabled(true);
                        Toast.makeText(activity, err.isEmpty() ? "darked.tif を保存しました" : "失敗: " + err, Toast.LENGTH_LONG).show();
                    });
                });
            }
        });


    }

    // darked.tif を書き出す. 成功時は空文字列, 失敗時はエラーメッセージ
    private String subtractDark(ContentResolver resolver, String lightName, Uri lightUri, Uri darkUri) {
        ContentValues values = new ContentValues();
        Uri uri3 = Cam.getUri(activity,"Documents/FUKASIS-app/imgs/" + lightName + "/", "darked.tif", "image/tiff",resolver , values);
        if (uri3 == null) {
            return "darked.tif を作成できません";
        }
        String err;
        try (ParcelFileDescriptor pfd1 = resolver.openFileDescriptor(lightUri, "r");
             ParcelFileDescriptor pfd2 = resolver.openFileDescriptor(darkUri, "r");
             ParcelFileDescriptor pfd3 = resolver.openFileDescriptor(uri3, "wt")) {
            if (pfd1 == null || pfd2 == null || pfd3 == null) {
                err = "ファイルを開けません";
            } else {
                err = processImgs(pfd1.getFd(), pfd2.getFd(), pfd3.getFd());
            }
        } catch (IOException | RuntimeException e) {
            e.printStackTrace();
            err = "ファイルを開けません: " + e.getMessage();
        }
        // 失敗したら中途半端な darked.tif を残さない (残すと csv 画面がそれを優先して読む)
        Cam.finishOutput(resolver, uri3, values, err.isEmpty());
        Log.d("a", err.isEmpty() ? "darked" : err);
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


    // 成功時は空文字列, 失敗時はエラーメッセージを返す
    public native String processImgs(int fd1, int fd2, int fd3);
}
