// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
package com.example.ssa;
import android.content.Intent;
import android.os.Bundle;
import com.google.android.material.snackbar.Snackbar;
import androidx.appcompat.app.AppCompatActivity;

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
                Toast.makeText(this, "ストレージフルアクセス権限があります", Toast.LENGTH_SHORT).show();
            }
        } else {
            // Android 10以下の場合は、通常のREAD/WRITEパーミッションを要求
            // （ここは既存のコードがあると思うので割愛します）
        }
    }
}

