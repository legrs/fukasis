package com.example.ssa;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.net.Uri;
import android.util.Log;
import android.os.Bundle;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Switch;
import android.widget.FrameLayout;
import android.widget.CompoundButton;

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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class CapActivity extends AppCompatActivity{

    private static final String TAG = "Camera2Native";
    private CameraDevice camDev;
    private CaptureRequest.Builder capRequestBuilder;
    private CameraCaptureSession capSession;
    private CameraCharacteristics camCharacteristics;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;

    private SoundPool soundPool;
    int alarmSound;
    private long expo = 200000000; // ns
    private int iso = 800; // iso
    private float fd = 1; // m?
    private int zoom = 0; //0:noZoom 1:focusing 2:pointing
    private boolean isLongExpo = false;
    private boolean isLine = true;

    private TextureView tv1;
    private TextureView tv2;
    private TextView indicator;
    private FrameLayout line;
    private EditText nameText;
    private EditText qtyText;
    private ImageReader rawImgReader;

    private String camId = "0";
    private ActivityCapBinding binding;

    private Cam cam;


    //@Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults){
        //super.onRequestPermissionResult(requestCode, permissions, grantResults);
        if(requestCode == 100 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED){
            cam.setupCam();
        }
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 長時間撮影で画面が消えないようにするため
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        binding = ActivityCapBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        //setContentView(R.layout.activity_main);

        // UIs
        Button zoomFF = binding.zoomFF;
        Button zoomFP = binding.zoomFP;
        Button capBtn = binding.cap;
        Button switchLine = binding.switchLine;
        tv1 = binding.tv1;
        tv2 = binding.tv2;
        nameText = binding.nameText;
        indicator = binding.indicator;
        qtyText = binding.qtyText;
        TextView focusTxt = binding.focusTxt;
        SeekBar focusBar = binding.focusBar;
        SeekBar lineBar = binding.lineBar;
        FrameLayout line = binding.line;
        TextView isoTxt = binding.isoTxt;
        SeekBar isoBar = binding.isoBar;
        Switch expoSw = binding.expoSwitch;
        TextView expoTxt = binding.expoTxt;
        SeekBar expoBar = binding.expoBar;
        focusTxt.setText(focusBar.getProgress() + "");
        isoTxt.setText(isoBar.getProgress() + "");
        expoTxt.setText(expoBar.getProgress() + "");
        Log.v("a","executed onCreate            a");

        switchLine.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                isLine = !isLine;
                if(isLine){
                    line.setAlpha(255);
                }else{
                    line.setAlpha(0);
                }
                
            }
        });
        capBtn.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                int qty = Integer.parseInt(qtyText.getText().toString());
                Log.d("a",String.format("start capture %d ms, %d, %f, %d枚,name:%s",(int)(expo/1000000L),iso,fd,qty,nameText.getText().toString()));
                cam.startCaptureSession(expo, iso , fd, qty, nameText.getText().toString(), indicator);
                Log.d("BUTTON", "start capture session！");
            }
        });
        zoomFF.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                if(zoom == 0){
                    zoomFF.setText("UNZOOM");
                    zoom = 1;
                    cam.changeValueOfPreview(0, -100, 0, 1);
                }else{
                    zoom = 0;
                    zoomFF.setText("ZOOM FOR FOCUSING");
                    cam.changeValueOfPreview(0, -100, 0, 0);
                }
            }
        });
        zoomFP.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                if(zoom == 0){
                    zoomFP.setText("UNZOOM");
                    zoom = 2;
                    cam.transformTextures(2);
                }else{
                    zoom = 0;
                    zoomFP.setText("ZOOM FOR POINTING");
                    cam.transformTextures(0);
                }
            }
        });
        expoSw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton btn, boolean b) {
                if(b){
                    isLongExpo = true;
                }else{
                    isLongExpo = false;
                }
            }
        });
        focusBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                fd = (float)i/100.0f;
                focusTxt.setText(fd + "");
                
                cam.changeValueOfPreview(0, fd, 0, -1);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        isoBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                switch(i){
                    case 1:
                        iso = 50;
                        break;
                    case 2:
                        iso = 64;
                        break;
                    case 3:
                        iso = 80;
                        break;
                    case 4:
                        iso = 100;
                        break;
                    case 5:
                        iso = 125;
                        break;
                    case 6:
                        iso = 160;
                        break;
                    case 7:
                        iso = 200;
                        break;
                    case 8:
                        iso = 250;
                        break;
                    case 9:
                        iso = 320;
                        break;
                    case 10:
                        iso = 400;
                        break;
                    case 11:
                        iso = 500;
                        break;
                    case 12:
                        iso = 640;
                        break;
                    case 13:
                        iso = 800;
                        break;
                    case 14:
                        iso = 1600;
                        break;
                    case 15:
                        iso = 3200;
                        break;
                }

                isoTxt.setText(iso + "");
                cam.changeValueOfPreview(iso, -100, 0, -1);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        expoBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                double ms;

                if(isLongExpo){
                    ms = Math.pow(10.0, 2.0 + (double)i*2.1461280356782/100.0);// log_10(30,000) = 4.4771212547197
                }else{
                    // min 0.04166 ms 
                    // 2.0(100ms) - -1.3802807343883 = 3.38028073439
                    ms = Math.pow(10.0, -1.3802807343883 + (double)i*3.38028073439/100.0);
                }
                expo = (long)(ms*1000000.0);
                expoTxt.setText(ms + "");
                cam.changeValueOfPreview(0, -100, expo, -1);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        lineBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                //0 ~ 1000
                if(zoom == 2){
                    line.setY(1800+i/10);
                }else{
                    line.setY(2050+i/10);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        // capture sounds
        AudioAttributes audioAttr = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        soundPool = new SoundPool.Builder().setAudioAttributes(audioAttr).setMaxStreams(2).build();
        alarmSound = soundPool.load(this, R.raw.technoalarm, 1);

        // cam object
        cam = new Cam(this, camId,soundPool,alarmSound, tv1, tv2);


        if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        }else{
            cam.setupCam();
        }
        cam.startBackgroundThread();
        // request camera permission
        //
        

    }
    @Override
    protected void onResume(){
        super.onResume();
        
        cam.startBackgroundThread();
        cam.setupCam();
    }
    @Override
    protected void onPause(){
        super.onPause();
        cam.closeCam();
        cam.stopBackgroundThread();
    }

/*
    @Override
    public void surfaceChanged(@NonNull SurfaceHolder surfaceHolder, int i, int i1, int i2) {

    }

    //@Override
    public void surfaceCreated(@NonNull SurfaceHolder holder){
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED){
            openCam(camId, holder.getSurface());
            //showStr(s);
        }
    }

    // when surface closed
    //@Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder){
        //closeCam();
    }
*/


}
