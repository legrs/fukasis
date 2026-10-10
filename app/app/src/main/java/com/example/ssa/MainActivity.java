// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
package com.example.ssa;
import android.content.Intent;
import android.os.Bundle;
import com.google.android.material.snackbar.Snackbar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import android.util.Log;
import android.view.View;
import android.widget.Button;

import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import com.example.ssa.databinding.ActivityMainBinding;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    private AppBarConfiguration appBarConfiguration;
    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        SystemBars.pad(binding.getRoot());

        Button cap = binding.capBtn;
        cap.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                Intent intent = new Intent(MainActivity.this, CapActivity.class);
                startActivity(intent);
            }
        });
        Button dark = binding.darkBtn;
        dark.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                Intent intent = new Intent(MainActivity.this, DarkActivity.class);
                startActivity(intent);
            }
        });
        Button csv = binding.csvBtn;
        csv.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                Intent intent = new Intent(MainActivity.this, CsvActivity.class);
                startActivity(intent);
            }
        });
        Button calibration = binding.calibBtn;
        calibration.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                Intent intent = new Intent(MainActivity.this, CalibActivity.class);
                startActivity(intent);
            }
        });
        Button view = binding.viewBtn;
        view.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                Intent intent = new Intent(MainActivity.this, ViewActivity.class);
                startActivity(intent);
            }
        });

        Button device = binding.deviceBtn;
        device.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                Intent intent = new Intent(MainActivity.this, DeviceActivity.class);
                startActivity(intent);
            }
        });

        // 日本語 <-> 英語 の切替。選んだ言語はアプリ単位で保存される
        Button lang = binding.langBtn;
        lang.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                String current = getResources().getConfiguration().getLocales().get(0).getLanguage();
                String next = "ja".equals(current) ? "en" : "ja";
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(next));
            }
        });

        checkAllFilesAccessPermission();

    }



    public void checkAllFilesAccessPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 (API 30) 以上の場合
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.addCategory("android.intent.category.DEFAULT");
                    intent.setData(Uri.parse(String.format("package:%s", getPackageName())));
                    startActivity(intent);
                } catch (Exception e) {
                    Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            } else {
                // 既に権限がある場合の処理
                Toast.makeText(this, R.string.toast_storage_access_granted, Toast.LENGTH_SHORT).show();
            }
        }
        // Android 10 (minSdk 29) では, このアプリが MediaStore に作ったファイルは権限なしで読み書きできる.
        // 感度データなど外部のファイルは ACTION_OPEN_DOCUMENT で選ぶので追加の権限は不要
    }
}

