// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
package com.example.ssa;
import java.io.OutputStream;
import java.util.Locale;
import android.widget.Toast;
import android.app.Activity;
import android.content.ContentValues;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
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
import android.widget.CheckBox;
import android.widget.CompoundButton;
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
    // 左の画像 (輝線用) の画面上の位置. 輝線の線を重ねるのに使う
    int[] pos1 = {0,0};
    float scale = 0.8F;
    // 表示位置 (画像を開いたときに端末プロファイルのスライダーの範囲から決める. Galaxy S22 では -1200, 350)
    int iv1_ofs = -1200;
    int iv2_ofs = 350;
    DeviceProfile profile;
    int imgWidth ;
    int imgHeight ;
    int dispWidth1 ;
    int dispWidth2 ;
    int dispHeight;
    int fol;
    int[] t = {0,0,0,0};
    float[] c = {0,0,0,0};
    SeekBar[] sb;
    TextView brightnessTxt;
    TextView[] tv;
    EditText[] et;
    FrameLayout[] line;
    // 追加の校正点. チェックを入れたものだけ校正に使う
    int[] tExtra = {0,0};
    CheckBox[] checkExtra;
    SeekBar[] sbExtra;
    TextView[] tvExtra;
    EditText[] etExtra;
    FrameLayout[] lineExtra;

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
        line[j].setX(pos1[0]+(t[j] +iv1_ofs)*scale);
        line[j].setY(pos[1]-50);
    }

    private void changeExtra(int j, int i){
        tvExtra[j].setText("" + i);
        tExtra[j] = (imgWidth - i);
        lineExtra[j].setX(pos1[0]+(tExtra[j] +iv1_ofs)*scale);
        lineExtra[j].setY(pos[1]-50);
    }

    private void setExtraEnabled(int j, boolean enabled){
        sbExtra[j].setEnabled(enabled);
        etExtra[j].setEnabled(enabled);
        lineExtra[j].setVisibility(enabled ? View.VISIBLE : View.INVISIBLE);
        if(enabled){
            changeExtra(j, sbExtra[j].getProgress());
        }
    }

    // プレビュー画像の表示上の明るさを変える (i=10 ごとに2倍)。書き出す校正データには影響しない
    private void changeBrightness(int i){
        float gain = (float)Math.pow(2.0, i / 10.0);
        ColorMatrix cm = new ColorMatrix();
        cm.setScale(gain, gain, gain, 1.0F);
        ColorMatrixColorFilter filter = new ColorMatrixColorFilter(cm);
        iv1.setColorFilter(filter);
        iv2.setColorFilter(filter);
        brightnessTxt.setText(getString(R.string.brightness_format, gain));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityCalibBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        SystemBars.pad(binding.getRoot());

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
        checkExtra = new CheckBox[]{binding.extraCheck1,binding.extraCheck2};
        sbExtra = new SeekBar[]{binding.sb6,binding.sb7};
        tvExtra = new TextView[]{binding.t6,binding.t7};
        etExtra = new EditText[]{binding.c5,binding.c6};
        lineExtra = new FrameLayout[]{binding.l6,binding.l7};
        for(int k=0; k<checkExtra.length; k++){
            final int j = k;
            setExtraEnabled(j, checkExtra[j].isChecked());
            checkExtra[j].setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton btn, boolean b) {
                    setExtraEnabled(j, b);
                }
            });
            sbExtra[j].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                    if(checkExtra[j].isChecked()){
                        changeExtra(j, i);
                    }
                }
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        }
        iv1 = binding.iv1;
        iv1.setScaleType(ImageView.ScaleType.MATRIX);
        iv2 = binding.iv2;
        iv2.setScaleType(ImageView.ScaleType.MATRIX);
        SeekBar brightnessBar = binding.brightnessBar;
        brightnessTxt = binding.brightnessTxt;
        changeBrightness(brightnessBar.getProgress());

        
        path_et1 = binding.input1;
        path_et2 = binding.input2;

        // スライダーの範囲は端末ごとに違う (0次光と輝線が写る位置は筐体とカメラで決まる)
        profile = DeviceProfiles.current(this);
        int[] folRange = profile.folProgress();
        int[] peakRange = profile.peakProgress();
        DeviceProfiles.applyRange(sb1, folRange);
        for (SeekBar bar : sb) {
            DeviceProfiles.applyRange(bar, peakRange);
        }
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
                        // exists
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
                    // 左の画像は輝線スライダーの範囲, 右の画像は 0次光スライダーの範囲の端が見えるようにずらす
                    iv1_ofs = -(imgWidth - profile.peakProgress()[1] + 100);
                    iv2_ofs = profile.folProgress()[0];
                    matrix.setScale(scale, scale);
                    matrix.postTranslate(scale*iv1_ofs, -(scale*imgHeight-dispHeight)/2);
                    iv1.setImageMatrix(matrix);

                    matrix = new Matrix();
                    matrix.setScale(scale, scale);
                    matrix.postTranslate(dispWidth2 - scale*(imgWidth-iv2_ofs), -(scale*imgHeight-dispHeight)/2);
                    iv2.setImageMatrix(matrix);

                    iv2.getLocationOnScreen(pos);
                    iv1.getLocationOnScreen(pos1);

                    // スライダーを動かさずに export しても現在の表示位置が使われるようにする
                    updateFol(sb1.getProgress());
                    for (int j = 0; j < sb.length; j++) {
                        changesb(j, sb[j].getProgress());
                    }
                }

            }
        });
        Button autoCalibBtn = binding.autoCalib;
        autoCalibBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runAutoCalibration(autoCalibBtn);
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
                // 校正点を集める. 基本の 4 本と, チェックの入っている追加分
                int extraCount = 0;
                for(int j=0; j<checkExtra.length; j++){
                    if(checkExtra[j].isChecked()){
                        extraCount++;
                    }
                }
                // folとの相対. t[] 自体は書き換えない (書き換えると2回目の export で値が壊れる)
                double[] tRel = new double[4 + extraCount];
                double[] cRef = new double[4 + extraCount];
                int n = 0;
                for(int i=0; i<4; i++){
                    try {
                        c[i] = Float.parseFloat(et[i].getText().toString().trim());
                    } catch (NumberFormatException e) {
                        Toast.makeText(activity, (i + 1) + "番目の波長が数値ではありません", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    tRel[n] = fol - t[i];
                    cRef[n] = c[i];
                    n++;
                }
                for(int j=0; j<checkExtra.length; j++){
                    if(checkExtra[j].isChecked()){
                        try {
                            cRef[n] = Float.parseFloat(etExtra[j].getText().toString().trim());
                        } catch (NumberFormatException e) {
                            Toast.makeText(activity, (5 + j) + "番目の波長が数値ではありません", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        tRel[n] = fol - tExtra[j];
                        n++;
                    }
                }
                for (int i = 0; i < n; i++) {
                    for (int k = i + 1; k < n; k++) {
                        if (tRel[i] == tRel[k]) {
                            Toast.makeText(activity, "同じ位置に2本以上の線があります。それぞれ別々の輝線に合わせてください", Toast.LENGTH_LONG).show();
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

                // 1 行目が位置, 2 行目が波長. 列の数が校正点の数
                // 小数点がカンマになる言語設定でも csv が壊れないように Locale を固定
                StringBuilder line1 = new StringBuilder();
                StringBuilder line2 = new StringBuilder();
                for(int i=0; i<n; i++){
                    if(i > 0){
                        line1.append(",");
                        line2.append(",");
                    }
                    line1.append(String.format(Locale.US, "%d", (int)tRel[i]));
                    line2.append(String.format(Locale.US, "%f", cRef[i]));
                }
                String dat = line1 + "\n" + line2;
                boolean saved = false;
                try(OutputStream output = resolver.openOutputStream(uriCsv, "wt")){
                    output.write(dat.getBytes("UTF-8"));
                    saved = true;
                    Log.d("a", "csv saved at "+uriCsv.toString());
                }catch(IOException e){
                    e.printStackTrace();
                }
                Cam.finishOutput(resolver, uriCsv, valuesCsv, saved);
                if (!saved) {
                    Toast.makeText(activity, "校正データの保存に失敗しました", Toast.LENGTH_SHORT).show();
                    return;
                }

                // この校正データで csv 画面が出力する波長の範囲を確かめて知らせる
                CalibrationValidator.Result check = CalibrationValidator.validate(tRel, cRef, fol);
                int wlMin = (int)Math.round(check.wavelengthMin);
                int wlMax = (int)Math.round(check.wavelengthMax);
                String message;
                switch(check.status){
                    case CalibrationValidator.TRUNCATED:
                        message = getString(R.string.calib_warning_truncated, wlMin, wlMax);
                        break;
                    case CalibrationValidator.NO_OUTPUT:
                        message = getString(R.string.calib_warning_no_output);
                        break;
                    default:
                        message = getString(R.string.calib_saved, wlMin, wlMax);
                        break;
                }
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
            }
        });
        brightnessBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                changeBrightness(i);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        // スクロールしても線が画像についてくるようにする
        binding.scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int x, int y, int oldX, int oldY) {
                if(imgWidth == 0){
                    return;
                }
                iv2.getLocationOnScreen(pos);
                iv1.getLocationOnScreen(pos1);
                binding.l1.setY(pos[1]-50);
                for(int j=0; j<4; j++){
                    line[j].setY(pos[1]-50);
                }
                for(int j=0; j<lineExtra.length; j++){
                    lineExtra[j].setY(pos[1]-50);
                }
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
    // 0次光と輝線を自動検出して sb1..sb5 に反映する. 画像の解析はバックグラウンドで行う
    private void runAutoCalibration(Button button) {
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
        // EditText に入っている波長をカタログとして使う (ユーザが変更している場合を尊重)
        double[] catalog = new double[et.length];
        for (int i = 0; i < et.length; i++) {
            try {
                catalog[i] = Double.parseDouble(et[i].getText().toString().trim());
            } catch (NumberFormatException e) {
                Toast.makeText(activity, (i + 1) + "番目の波長が数値ではありません", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        final int width = imgWidth;
        final int folMin = binding.sb1.getMin();
        final int folMax = binding.sb1.getMax();
        final int peakMin = sb[0].getMin();
        final int peakMax = sb[0].getMax();
        final double[] nmPerPx = profile.nmPerPx();
        final int cfa = DeviceProfiles.cfa(this);
        ContentResolver resolver = getContentResolver();

        button.setEnabled(false);
        Toast.makeText(activity, "自動検出中…", Toast.LENGTH_SHORT).show();
        AutoCalibration.EXECUTOR.execute(() -> {
            String error = null;
            AutoCalibration.Analysis analysis = null;
            SpectrumCalibrator.CalibrationResult result = null;
            try {
                analysis = AutoCalibration.analyzeSequence(resolver, seq, profile, cfa);
                result = SpectrumCalibrator.calibrate(analysis.image, width, folMin, folMax, peakMin, peakMax, catalog,
                        nmPerPx[0], nmPerPx[1]);
            } catch (SpectrumCalibrator.CalibrationException e) {
                error = e.getMessage();
            } catch (RuntimeException e) {
                Log.e("CalibAuto", "auto detect failed", e);
                error = String.valueOf(e.getMessage());
            }
            final String err = error;
            final AutoCalibration.Analysis a = analysis;
            final SpectrumCalibrator.CalibrationResult r = result;
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                button.setEnabled(true);
                if (err != null) {
                    Toast.makeText(activity, "自動検出に失敗しました: " + err, Toast.LENGTH_LONG).show();
                    return;
                }
                applyAutoCalibration(seq, a, r);
            });
        });
    }

    private void applyAutoCalibration(String seq, AutoCalibration.Analysis analysis, SpectrumCalibrator.CalibrationResult r) {
        binding.sb1.setProgress(r.folProgress);
        // setProgress は値が変わらないと listener を呼ばないので, fol と線の位置は明示的に反映する
        fol = imgWidth - r.folProgress;
        binding.t1.setText("" + r.folProgress);
        binding.l1.setX(pos[0]+dispWidth2+(-imgWidth + fol + iv2_ofs)*scale);
        binding.l1.setY(pos[1]-50);
        for (int i = 0; i < sb.length; i++) {
            sb[i].setProgress(r.peakProgress[i]);
            changesb(i, r.peakProgress[i]);
        }
        // 保存名が空なら観測名をそのまま使う (例: fluorescent_260314_1)
        if (path_et2.getText().toString().trim().isEmpty()) {
            path_et2.setText(seq);
        }
        String message = String.format(Locale.US,
                "自動検出完了 (%s): 0次光 %d, 輝線 %d 本中 4 本を対応付け, 直線からのずれ %.2f nm. 確認して EXPORT CSV を押してください",
                analysis.fileName, r.folProgress, r.peakCount, r.match.rmsNm);
        String warning = SpectrumCalibrator.bandOffsetWarning(analysis.image, profile.bandWidth(), profile.bandCenter());
        if (warning != null) {
            message += "\n注意: " + warning;
        }
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
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
