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
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;

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

    private void updateFol(int i){
        binding.t1.setText("" + i);
        fol = imgWidth - i;
        binding.l1.setX(pos[0]+dispWidth2+(-imgWidth + fol + iv2_ofs)*scale);
        binding.l1.setY(pos[1]-50);
    }

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
        sb = new SeekBar[]{binding.sb2,binding.sb3,binding.sb4,binding.sb5};
        tv = new TextView[]{binding.t2,binding.t3,binding.t4,binding.t5};
        et = new EditText[]{binding.c1,binding.c2,binding.c3,binding.c4};
        //SeekBar sb3 = binding.sb3;
        //TextView t3 = binding.t3;
        //SeekBar sb4 = binding.sb4;
        //TextView t4 = binding.t4;
        //SeekBar sb5 = binding.sb5;
        //TextView t5 = binding.t5;
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

                    // スライダーを動かさずに export しても現在の表示位置が使われるようにする
                    updateFol(sb1.getProgress());
                    for (int j = 0; j < sb.length; j++) {
                        changesb(j, sb[j].getProgress());
                    }
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
                String name = path_et2.getText().toString().trim();
                if (name.isEmpty()) {
                    Toast.makeText(activity, "Calibration Data Name を入力してください", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (imgWidth == 0) {
                    Toast.makeText(activity, "先に open image してください", Toast.LENGTH_SHORT).show();
                    return;
                }
                for(int i=0; i<4; i++){
                    try {
                        c[i] = Float.parseFloat(et[i].getText().toString().trim());
                    } catch (NumberFormatException e) {
                        Toast.makeText(activity, (i + 1) + "番目の波長が数値ではありません", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
                // folとの相対. t[] 自体は書き換えない (書き換えると2回目の export で値が壊れる)
                int[] rel = new int[4];
                for(int i=0; i<4; i++){
                    rel[i] = fol - t[i];
                }
                for (int i = 0; i < 4; i++) {
                    for (int k = i + 1; k < 4; k++) {
                        if (rel[i] == rel[k]) {
                            Toast.makeText(activity, "同じ位置に2本以上の線があります。4本を別々の輝線に合わせてください", Toast.LENGTH_LONG).show();
                            return;
                        }
                    }
                }

                ContentResolver resolver = activity.getContentResolver();
                ContentValues valuesCsv = new ContentValues();
                Uri uriCsv = Cam.getUri(activity,"Documents/FUKASIS-app/csv/calibdata/", name + ".csv", "text/csv",resolver , valuesCsv);
                if (uriCsv == null) {
                    Toast.makeText(activity, "保存先のファイルを作成できません", Toast.LENGTH_SHORT).show();
                    return;
                }

                // 小数点がカンマになるロケールでも読めるよう Locale.US で書く
                String dat = String.format(Locale.US, "%d,%d,%d,%d\n%f,%f,%f,%f",rel[0],rel[1],rel[2],rel[3],c[0],c[1],c[2],c[3]);
                boolean saved = false;
                try(OutputStream output = resolver.openOutputStream(uriCsv, "wt")){
                    output.write(dat.getBytes("UTF-8"));
                    saved = true;
                    Log.d("a", "csv saved at "+uriCsv.toString());
                }catch(IOException e){
                    e.printStackTrace();
                }
                Cam.finishOutput(resolver, uriCsv, valuesCsv, saved);
                Toast.makeText(activity, saved ? "校正データを保存しました" : "校正データの保存に失敗しました", Toast.LENGTH_SHORT).show();
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
