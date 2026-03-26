/*
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
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

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

    // Used to load the 'ssa' library on application startup.
    static {
        System.loadLibrary("ssa");
    }

    private TextureView tv;
    private ImageReader rawImgReader;
    //background thread
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private String camId = "0";
    private ActivityCapBinding binding;

    private void showStr(String s){
        TextView tv = binding.focusTxt;
        tv.setText(s);
    }




    private void setupCam(int width, int height){
        CameraManager manager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
        try{

            camCharacteristics = manager.getCameraCharacteristics(camId);
            StreamConfigurationMap map = camCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR);
            Size largestRaw = rawSizes[0];
            for(Size s : rawSizes){
                if(s.getWidth()*s.getHeight() > largestRaw.getWidth()*largestRaw.getHeight())
                    largestRaw = s;
            }
            rawImgReader = ImageReader.newInstance(largestRaw.getWidth(), largestRaw.getHeight(), ImageFormat.RAW_SENSOR, 2);
            rawImgReader.setOnImageAvailableListener(onRawImageAvailableListener, backgroundHandler);

            openCam();
        }catch(CameraAccessException e){
            e.printStackTrace();
        }
    }
    private void openCam(){
        Log.v("a", "opencam()");
        try{
            CameraManager manager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
            if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED){
                manager.openCamera(camId, stateCallback, backgroundHandler);
                Log.v("a", "openCamera()");
                
            }else{

                Log.v("a", "no permission ");
            }
        }catch(CameraAccessException e){
            e.printStackTrace();
        }
    }
    private void closeCam(){
        Log.v("a", "closeCam()");
        if(capSession != null){
            capSession.close();
            capSession = null;
        }
        if(camDev != null){
            camDev.close();
            camDev = null;
        }
        if(rawImgReader != null){
            rawImgReader.close();
            rawImgReader = null;
        }
    }
    private void createCamPreviewSession(){
        Log.v("a", "createCamPreviewSession()");

        try{
            Surface texSurface = new Surface(tv.getSurfaceTexture());
            Surface rawFurface = rawImgReader.getSurface();

            capRequestBuilder = camDev.createCaptureRequest(camDev.TEMPLATE_PREVIEW);
            capRequestBuilder.addTarget(texSurface);
            
            camDev.createCaptureSession(Arrays.asList(texSurface, rawFurface), new CameraCaptureSession.StateCallback(){
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session){
                    capSession = session;
                    capRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                    capRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 80000000L);
                    capRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, 3200);
                    capRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                    capRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, fd);

                    //preview
                    try{
                        capSession.setRepeatingRequest(capRequestBuilder.build(), null, backgroundHandler);
                    }catch(CameraAccessException e){
                        e.printStackTrace();
                    }
                }
                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session){
                    
                }
            }, null);
        }catch(CameraAccessException e){
            e.printStackTrace();
        }
    }
    private void capture(){
        Log.v("a", "capture() called");
        if(camDev == null) return;
        Log.v("a", "camDev != null");
        try{
            final CaptureRequest.Builder capBuilder = camDev.createCaptureRequest(camDev.TEMPLATE_STILL_CAPTURE);
            capBuilder.addTarget(rawImgReader.getSurface());

            capBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            capBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expo);
            capBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            capBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            capBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, fd);

            Log.v("a", String.format("start capture %d sec",(int)(expo/1000000L)));
            CameraCaptureSession.CaptureCallback capCallback = new CameraCaptureSession.CaptureCallback(){
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result){
                    super.onCaptureCompleted(session, request, result);
                   
                    lastCapResult = result;

                }
            };
            capSession.capture(capBuilder.build(), capCallback, backgroundHandler);
            
        }catch(CameraAccessException e){
            e.printStackTrace();
        }

    }
    private TotalCaptureResult lastCapResult;

    private final ImageReader.OnImageAvailableListener onRawImageAvailableListener = new ImageReader.OnImageAvailableListener(){
        @Override
        public void onImageAvailable(ImageReader reader){
            Log.v("a", "img available");
            Image img = null;
            try{
                img = reader.acquireNextImage();
                if(lastCapResult != null){
                    // left vol. right vol. priority loop speed
                    soundPool.play(alarmSound, 1.0f, 1.0f, 0, 0, 1);

                    saveDNG(img, lastCapResult);
                }else{
                    Log.v("a", "lastCapResult == null");
                }
                Image.Plane plane = img.getPlanes()[0];
                ByteBuffer buff = plane.getBuffer();

                File file = new File(getExternalFilesDir(null), "tmp_csv.csv");
                //FileOutputStream output = new FileOutputStream(file)
                // c++にすべてを渡す
                processRawImg(buff, img.getWidth(), img.getHeight(), plane.getRowStride(), file.getAbsolutePath());


                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, "CSV_" + System.currentTimeMillis() + ".csv");
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/SSA/csvs");

                ContentResolver resolver = getContentResolver();
                Uri uri = resolver.insert(MediaStore.Files.getContentUri("external"), values);

                if(uri != null){
                    try(FileInputStream input = new FileInputStream(file)){
                        OutputStream output = getContentResolver().openOutputStream(uri);

                        // copy to mediastore
                        byte[] buff1 = new byte[1024 * 4];
                        int length;
                        while((length = input.read(buff1)) > 0){
                            output.write(buff1, 0, length);
                        }

                        values.clear();
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        resolver.update(uri, values, null, null);

                        Log.d("a", "csv saved at "+uri.toString());
                        /*
                        String[] fileNames = getExternalFilesDir(null).list();
                        for(int i=0; i<fileNames.length; i++){
                            Log.d("a", fileNames[i]);
                        }
                        */
                    }catch(IOException e){
                        e.printStackTrace();
                        resolver.delete(uri, null, null);
                    }
                }

            }catch(Exception e){
                e.printStackTrace();
            }finally{
                if(img != null) img.close();
            }
        }
    };

    private void saveDNG(Image img, TotalCaptureResult result){
        DngCreator dngCreator = new DngCreator(camCharacteristics, result);

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_" + System.currentTimeMillis() + ".dng");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/SSA/imgs");

        Uri uri = getContentResolver().insert(MediaStore.Files.getContentUri("external"), values);

        try{
            if(uri != null){
                try(OutputStream output = getContentResolver().openOutputStream(uri)){
                    dngCreator.writeImage(output, img);
                    Log.d(TAG, "DNG saved at " + uri.toString());

                    /*
                    String[] fileNames = getExternalFilesDir(null).list();
                    for(int i=0; i<fileNames.length; i++){
                        Log.d("a", fileNames[i]);
                    }
                    */
                }
            }
        }catch(IOException e){
            e.printStackTrace();
        }finally{
            dngCreator.close();
        }


        /*
        File file = new File(getExternalFilesDir(null), "IMG_" + System.currentTimeMillis() + ".dng");
        // ()のなかのfileoutputstreamは自動で閉じられる
        try(FileOutputStream output = new FileOutputStream(file)){
            dngCreator.writeImage(output, img);
            Log.d(TAG, "DNG saved at " + file.getAbsolutePath());

            String[] fileNames = getExternalFilesDir(null).list();
            for(int i=0; i<fileNames.length; i++){
                Log.d("a", fileNames[i]);
            }
        }catch(IOException e){
            e.printStackTrace();
        }finally{
            dngCreator.close();
        }
        */
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback(){
        @Override
        public void onOpened(@NonNull CameraDevice cam){
            camDev = cam;
            createCamPreviewSession();
        }
        @Override
        public void onDisconnected(@NonNull CameraDevice cam){
            cam.close();
        }
        @Override
        public void onError(@NonNull CameraDevice cam, int error){
            cam.close();
        }
    };
    private final TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener(){
        @Override
        public void onSurfaceTextureAvailable(@NonNull android.graphics.SurfaceTexture surface, int width, int height){
            setupCam(width, height);
        }
        @Override
        public void onSurfaceTextureSizeChanged(@NonNull android.graphics.SurfaceTexture surface, int width, int height){
        }
        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull android.graphics.SurfaceTexture surface){
            return false;
        }
        @Override
        public void onSurfaceTextureUpdated(@NonNull android.graphics.SurfaceTexture surface){
        }
    };
    private void startBackgroundThread(){
        backgroundThread = new HandlerThread("Camerabackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }
    private void stopBackgroundThread(){
        if(backgroundThread != null){
            backgroundThread.quitSafely();
            try{
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            }catch(InterruptedException e){
                e.printStackTrace();
            }
        }
    }







    //@Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults){
        //super.onRequestPermissionResult(requestCode, permissions, grantResults);
        if(requestCode == 100 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED){
            if(tv.isAvailable()){
                setupCam(tv.getWidth(), tv.getHeight());
            }else{
                tv.setSurfaceTextureListener(textureListener);
            }
        }
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityCapBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        //setContentView(R.layout.activity_main);
        // Example of a call to a native method
        Button capBtn = binding.cap;
        capBtn.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                capture();
                Log.d("BUTTON", "capture！");
            }
        });
        tv = binding.tv;
        TextView focusTxt = binding.focusTxt;
        SeekBar focusBar = binding.focusBar;
        TextView expoTxt = binding.expoTxt;
        SeekBar expoBar = binding.expoBar;
        focusTxt.setText(focusBar.getProgress() + "");
        expoTxt.setText(expoBar.getProgress() + "");
        Log.v("a","executed onCreate            a");
        focusBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                focusTxt.setText((float)i/10.0 + "");
                fd = (float)i/10.0f;
                
                capRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                capRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, fd);

                //preview
                try{
                    capSession.setRepeatingRequest(capRequestBuilder.build(), null, backgroundHandler);
                }catch(CameraAccessException e){
                    e.printStackTrace();
                }
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
                expoTxt.setText(i + "");
                expo = (long)i*1000000;
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        AudioAttributes audioAttr = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        soundPool = new SoundPool.Builder().setAudioAttributes(audioAttr).setMaxStreams(2).build();
        alarmSound = soundPool.load(this, R.raw.technoalarm, 1);






        if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        }else{
            if(tv.isAvailable()){
                setupCam(tv.getWidth(), tv.getHeight());
            }else{
                tv.setSurfaceTextureListener(textureListener);
            }
        }
        startBackgroundThread();
        // request camera permission
        //
        

    }
    @Override
    protected void onResume(){
        super.onResume();
        
        startBackgroundThread();
        if(tv.isAvailable()){
            setupCam(tv.getWidth(), tv.getHeight());
        }else{
            tv.setSurfaceTextureListener(textureListener);
        }
    }
    @Override
    protected void onPause(){
        super.onPause();
        closeCam();
        stopBackgroundThread();
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


    /**
     * A native method that is implemented by the 'simple_spectroscope' native library,
     * which is packaged with this application.
     */
    /*
    public native String stringFromJNI();
    public native void closeCam();
    public native String openCam(String camIdStr, Object surface);
    public native void setPM(int selection, int value);
    */
    public native String processRawImg(ByteBuffer buff, int width, int height, int rowStride, String filepath);

}
*/
