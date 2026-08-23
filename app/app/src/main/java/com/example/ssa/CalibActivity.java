// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
package com.example.ssa;
import java.io.OutputStream;
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

import com.example.ssa.databinding.ActivityCalibBinding;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import android.content.ContentResolver;
import android.provider.MediaStore;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.IOException;

public class CalibActivity extends AppCompatActivity{

    //435.8, 546.1, 588.0, 611.6
    private ImageView iv1;
    private ImageView iv2;
    private EditText path_et1; //et=EditText
    private EditText path_et2; //et=EditText

    private ActivityCalibBinding binding;
    private Activity activity = this;

    int[] pos = {0,0};
    float scale = 0.8F;
    int iv1_ofs = -1200;
    int iv2_ofs = 350;
    int imgWidth ;
    int imgHeight ;
    int dispWidth1 ;
    int dispWidth2 ;
    int dispHeight;
    int fol;
    int[] t = {0,0,0,0};
    float[] c = {0,0,0,0};
    SeekBar[] sb;
    TextView[] tv;
    EditText[] et;
    FrameLayout[] line;

    private void changesb(int j, int i){
        tv[j].setText("" + i);
        t[j] = (imgWidth - i);
        Log.d("a", Integer.toString(fol - t[j]));
        line[j].setX((t[j] +iv1_ofs)*scale);
        line[j].setY(pos[1]-50);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityCalibBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        //setContentView(R.layout.activity_main);

        // UIs
        Button openBtn = binding.open;
        Button exportBtn = binding.export;
        SeekBar sb1 = binding.sb1;
        TextView t1 = binding.t1;
        sb = new SeekBar[]{binding.sb2,binding.sb3,binding.sb4,binding.sb5};
        tv = new TextView[]{binding.t2,binding.t3,binding.t4,binding.t5};
        et = new EditText[]{binding.c1,binding.c2,binding.c3,binding.c4};
        //SeekBar sb3 = binding.sb3;
        //TextView t3 = binding.t3;
        //SeekBar sb4 = binding.sb4;
        //TextView t4 = binding.t4;
        //SeekBar sb5 = binding.sb5;
        //TextView t5 = binding.t5;
        FrameLayout l1 = binding.l1;
        line = new FrameLayout[]{binding.l2,binding.l3,binding.l4,binding.l5};
        iv1 = binding.iv1;
        iv1.setScaleType(ImageView.ScaleType.MATRIX);
        iv2 = binding.iv2;
        iv2.setScaleType(ImageView.ScaleType.MATRIX);

        
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

                //* ファイル名を指定
                String filename = "stacked.jpg";
                String[] selectionArgs = new String[]{filename, filepath};

                try(Cursor cursor = resolver.query(
                            collection,
                            new String[]{MediaStore.MediaColumns._ID},
                            selection,
                            selectionArgs,
                            null)){
                    //* */ filenameをlog
                    Log.d("a", "filename: " + filename);
                    
                    if(cursor != null && cursor.moveToFirst()){
                        long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                        // exsists
                        uri = ContentUris.withAppendedId(collection, id);
                        Log.d("a","ありましたよっ！");
                    }else{

                        Log.d("a","な、ないです…");
                        Log.d("a","uri = " + uri);
                        Log.d("a","filepath = " + filepath);
                    }

                }
                if(uri != null){
                    iv1.setImageURI(uri);
                    iv2.setImageURI(uri);
                    Log.d("a", "open");
                    Matrix matrix = new Matrix();
                    dispWidth1 = iv1.getWidth();
                    dispWidth2 = iv2.getWidth();
                    dispHeight = iv1.getHeight();
                    imgWidth = iv1.getDrawable().getIntrinsicWidth();
                    imgHeight = iv1.getDrawable().getIntrinsicHeight();
                    Log.d("a","" + dispWidth1);
                    Log.d("a","" + dispWidth2);
                    Log.d("a","" + dispHeight);
                    Log.d("a","" + imgWidth);
                    Log.d("a","" + imgHeight);
                    matrix.setScale(scale, scale);
                    matrix.postTranslate(scale*iv1_ofs, -(scale*imgHeight-dispHeight)/2);
                    iv1.setImageMatrix(matrix);

                    matrix = new Matrix();
                    matrix.setScale(scale, scale);
                    matrix.postTranslate(dispWidth2 - scale*(imgWidth-iv2_ofs), -(scale*imgHeight-dispHeight)/2);
                    iv2.setImageMatrix(matrix);

                    iv2.getLocationOnScreen(pos);
                }

            }
        });
        Button autoCalibBtn = binding.autoCalib;
        autoCalibBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String seq = path_et1.getText().toString().trim();
                if (seq.isEmpty()) {
                    android.widget.Toast.makeText(activity, "Sequence Nameを入力してください", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                ContentResolver resolver = getContentResolver();
                Uri collection = MediaStore.Files.getContentUri("external");
                String filepath = "Documents/FUKASIS-app/imgs/" + seq + "/";
                String[] tifNames = {"stacked.tif", "darked.tif"};
                Uri tifUri = null;
                for (String name : tifNames) {
                    String sel = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
                    String[] args = {name, filepath};
                    try (Cursor c = resolver.query(collection, new String[]{MediaStore.MediaColumns._ID}, sel, args, null)) {
                        if (c != null && c.moveToFirst()) {
                            long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                            tifUri = ContentUris.withAppendedId(collection, id);
                            break;
                        }
                    }
                }
                if (tifUri == null) {
                    android.widget.Toast.makeText(activity, "stacked.tifが見つかりません", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                if (imgWidth == 0) {
                    android.widget.Toast.makeText(activity, "先に open image してください", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(tifUri, "r")) {
                    if (pfd == null) {
                        android.widget.Toast.makeText(activity, "ファイルを開けません", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int folEst = SpectrumCalibrator.detectFolNative(pfd.getFd());
                    if (folEst < 0) {
                        android.widget.Toast.makeText(activity, "Fol検出失敗", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // SeekBar sb1 は fol を表す: fol = imgWidth - progress
                    int sb1Progress = imgWidth - folEst;
                    if (sb1Progress < 350) sb1Progress = 350;
                    if (sb1Progress > 600) sb1Progress = 600;
                    binding.sb1.setProgress(sb1Progress);
                    // fol は sb1 リスナーで更新されるため手動でもセット
                    fol = folEst;

                    String peaksStr = SpectrumCalibrator.detectPeaksNative(pfd.getFd(), folEst);
                    if (peaksStr == null || peaksStr.isEmpty()) {
                        android.widget.Toast.makeText(activity, "ピーク検出失敗: 手動で合わせてください", android.widget.Toast.LENGTH_LONG).show();
                        return;
                    }
                    String[] parts = peaksStr.split(",");
                    int[] peaks = new int[parts.length];
                    for (int i = 0; i < parts.length; i++) {
                        peaks[i] = Integer.parseInt(parts[i].trim());
                    }
                    if (peaks.length == 0) {
                        android.widget.Toast.makeText(activity, "ピークが見つかりません", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 強度は不明なので null、デフォルトカタログで自動選択
                    double[] catalog = SpectrumCalibrator.DEFAULT_CATALOG;
                    // EditText に現在入っている波長をカタログとして使う (ユーザが変更している場合を尊重)
                    try {
                        double[] custom = new double[4];
                        for (int i = 0; i < 4; i++) custom[i] = Double.parseDouble(et[i].getText().toString());
                        catalog = custom;
                    } catch (Exception ignored) {
                    }
                    int[] selected = SpectrumCalibrator.selectBestPeaks(peaks, null, catalog, 4);
                    if (selected.length < 4) {
                        android.widget.Toast.makeText(activity, "検出ピークが4本未満: " + peaks.length + "本", android.widget.Toast.LENGTH_LONG).show();
                    }
                    // SeekBar sb2..sb5 に反映: progress = imgWidth - x
                    for (int i = 0; i < Math.min(selected.length, sb.length); i++) {
                        int prog = imgWidth - selected[i];
                        if (prog < 1800) prog = 1800;
                        if (prog > 2900) prog = 2900;
                        sb[i].setProgress(prog);
                    }
                    android.widget.Toast.makeText(activity, "Auto検出完了: fol=" + folEst + " peaks=" + peaksStr, android.widget.Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Log.e("CalibAuto", "auto detect failed", e);
                    android.widget.Toast.makeText(activity, "Auto失敗: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
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
        exportBtn.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                ContentResolver resolver = activity.getContentResolver();

                ContentValues valuesCsv = new ContentValues();
                Uri uriCsv = Cam.getUri(activity,"Documents/FUKASIS-app/csv/calibdata/", path_et2.getText().toString() + ".csv", "text/csv",resolver , valuesCsv);

                if(uriCsv != null){
                    try(OutputStream output = activity.getContentResolver().openOutputStream(uriCsv)){
                        for(int i=0; i<4; i++){
                            c[i] = Float.parseFloat(et[i].getText().toString());
                        }
                        for(int i=0; i<4; i++){
                            t[i] = fol - t[i];
                            //folとの相対
                        }
                        String dat = String.format("%d,%d,%d,%d\n%f,%f,%f,%f",t[0],t[1],t[2],t[3],c[0],c[1],c[2],c[3]);

                        output.write(dat.getBytes("UTF-8"));

                        valuesCsv.clear();
                        valuesCsv.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        resolver.update(uriCsv, valuesCsv, null, null);

                        Log.d("a", "csv saved at "+uriCsv.toString());
                    }catch(IOException e){
                        e.printStackTrace();
                        resolver.delete(uriCsv, null, null);
                    }
                }

            }
        });
        sb1.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                Log.d("a","" + i);
                t1.setText("" + i);
                fol = imgWidth - i;
                l1.setX(pos[0]+dispWidth2+(-imgWidth + fol + iv2_ofs)*scale);
                l1.setY(pos[1]-50);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        sb[0].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                changesb(0,i);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        sb[1].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                changesb(1,i);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        sb[2].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                changesb(2,i);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        sb[3].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                changesb(3,i);
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


}
