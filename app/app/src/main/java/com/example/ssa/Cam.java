package com.example.ssa;
import android.content.ContentUris;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.view.TextureView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.app.Activity;
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
import android.widget.EditText;
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
import android.database.Cursor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class Cam{

    private static final String TAG = "Camera2Native";
    private CameraDevice camDev;
    private CaptureRequest.Builder capRequestBuilder;
    private CameraCaptureSession capSession;
    private CameraCharacteristics camCharacteristics;
    private CaptureRequest.Builder capSequenceBuilder;
    private SoundPool soundPool;
    int alarmSound;
    private String camId = "0";
    private int expo,iso;
    private float fd;

    private int maxW,maxH;
    private float maxZoom; //8.0

    private Activity activity;

    // Used to load the 'ssa' library on application startup.
    static {
        System.loadLibrary("ssa");
    }

    private TextureView tv1;
    private TextureView tv2;
    private TextView indicator;
    private ImageReader rawImgReader;
    //background thread
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private ActivityCapBinding binding;

    private boolean doPreview;
    private boolean isCapturing = false;

    private int sequrnceLength = 1;
    private String sequenceName = "test";
    private int currentCount = 0;

    // constructor
    public Cam(Activity activity, String camId, SoundPool soundPool, int alarmSound){
        this.activity = activity;
        this.camId = camId;
        this.soundPool = soundPool;
        this.alarmSound = alarmSound;
        this.doPreview = false;
    }
    public Cam(Activity activity, String camId, SoundPool soundPool, int alarmSound, TextureView tv1, TextureView tv2){
        this.activity = activity;
        this.camId = camId;
        this.soundPool = soundPool;
        this.alarmSound = alarmSound;
        this.tv1 = tv1;
        this.tv2 = tv2;
        this.doPreview = true;
    }

    private Uri getUri(String path, String name, String type,ContentResolver resolver,ContentValues values){
        Uri collection = MediaStore.Files.getContentUri("external");
        Uri uri = null;
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
        String[] selectionArgs = new String[]{name, path};

        try(Cursor cursor = resolver.query(
                    collection,
                    new String[]{MediaStore.MediaColumns._ID},
                    selection,
                    selectionArgs,
                    null)){
            if(cursor != null && cursor.moveToFirst()){
                Log.d("a","ありましたよっ！");
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                // exsists
                uri = ContentUris.withAppendedId(collection, id);
            }else{
                Log.d("a","な、ないです…");
            }

        }

        if(uri == null){
            // does not exsists
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, type);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, path);
            uri = activity.getContentResolver().insert(MediaStore.Files.getContentUri("external"), values);
        }
        return uri;
    }

        
    private void setup(){
        CameraManager manager = (CameraManager)activity.getSystemService(Context.CAMERA_SERVICE);
        try{

            camCharacteristics = manager.getCameraCharacteristics(camId);
            StreamConfigurationMap map = camCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            maxZoom = camCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            Log.d("a", "maxzoom :" + maxZoom); //8.0
            Size[] rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR);
            Size largestRaw = rawSizes[0];
            for(Size s : rawSizes){
                if(s.getWidth()*s.getHeight() > largestRaw.getWidth()*largestRaw.getHeight())
                    largestRaw = s;
            }
            maxW = largestRaw.getWidth();
            maxH = largestRaw.getHeight();
            rawImgReader = ImageReader.newInstance(maxW, maxH, ImageFormat.RAW_SENSOR, 2);
            rawImgReader.setOnImageAvailableListener(onRawImageAvailableListener, backgroundHandler);

            openCam();
        }catch(CameraAccessException e){
            e.printStackTrace();
        }
    }

    public void setupCam(){
        if(tv1.isAvailable() && tv2.isAvailable()){
            setup();
        }else{
            setTextureListener(tv1);
            setTextureListener(tv2);
        }
    }
    public void openCam(){
        Log.v("a", "opencam()");
        try{
            CameraManager manager = (CameraManager)activity.getSystemService(Context.CAMERA_SERVICE);
            manager.openCamera(camId, stateCallback, backgroundHandler);
        }catch(SecurityException e){
            e.printStackTrace();
        }catch(Exception e){
            e.printStackTrace();
        }
    }
    public void closeCam(){
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
    public void createCamPreviewSession(){
        Log.v("a", "createCamPreviewSession()");

        Surface texSurface1 = new Surface(tv1.getSurfaceTexture());
        Surface texSurface2 = new Surface(tv2.getSurfaceTexture());
        Surface rawSurface = rawImgReader.getSurface();
        try{

            capRequestBuilder = camDev.createCaptureRequest(camDev.TEMPLATE_PREVIEW);
            capRequestBuilder.addTarget(texSurface1);
            capRequestBuilder.addTarget(texSurface2);

            //これをやるとcapture()した後連続で写真が保存されつづける
            //capRequestBuilder.addTarget(rawSurface);

            
            // preview
            camDev.createCaptureSession(Arrays.asList(texSurface1, texSurface2, rawSurface), new CameraCaptureSession.StateCallback(){
            //camDev.createCaptureSession(Arrays.asList(texSurface1), new CameraCaptureSession.StateCallback(){
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session){
                    capSession = session;
            long maxExpo = camCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE).getUpper();
            long minExpo = camCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE).getLower();
            Log.d("a", "max expose time : " + maxExpo + "min expose time : " + minExpo);
                    capRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                    capRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 80000000L);
                    capRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, 3200);
                    capRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                    capRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, -1.0f);
                    //EIS
                    capRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
                    //OIS
                    capRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);   

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
    // iso 50,64,80,100,
    //     125,160,200,250,
    //     320,400,500,640,
    //     800,1600,3200
    public void changeValueOfPreview(int iso, float fd, long expo, int zoom){

        if(iso != 0){
            capRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
        }
        if(fd != -100){
            capRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            capRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, fd);
        }
        if(expo != 0){
            capRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            capRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expo);
        }
        if(zoom != -1){
            Rect sensorRect = camCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if(zoom == 0){
                transformTextures(0);
                capRequestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1.0f);
                capRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, sensorRect);
            }else if(zoom == 1){
                transformTextures(1);
                int cropW = sensorRect.width() / 4;
                //int cropH = cropW * maxH/maxW;
                int cropH = sensorRect.height() / 4;
                int cropX = (sensorRect.width() - cropW) / 2;
                int cropY = (int)(0.4F*(float)sensorRect.height() - (float)cropH/2.0F);

                Log.d("a",String.format("%d,%d,%d,%d", cropX, cropY, cropX + cropW, cropY + cropH));
                
                Rect zoomRect = new Rect(cropX, cropY, cropX + cropW, cropY + cropH);
                
                //capRequestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, 3.0f);
                //capRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
                capRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoomRect);
            }
        }

        //preview
        try{
            capSession.setRepeatingRequest(capRequestBuilder.build(), null, backgroundHandler);
        }catch(CameraAccessException e){
            e.printStackTrace();
        }
    }
    public void startCaptureSession(long expo, int iso, float fd, int qty, String name, TextView indicator){
        if(camDev == null) return;

            try{
                capSession.abortCaptures();
            }catch(CameraAccessException e){
                e.printStackTrace();
            }
        sequrnceLength = qty;
        sequenceName = name;
        prepare(maxW,maxH);
        this.expo = (int)(expo/1000000L);
        this.iso = iso;
        this.fd = fd;
        this.indicator = indicator;
        currentCount = 0;
        Log.d("a",String.format("Capturing…\n%s, %d ms, %d, %f\n%d/%d done",name,expo,iso,fd,currentCount,qty));
        //indicator.setText(String.format("Capturing…\n%s, %d ms, %d, %f\n%d/%d done",name,expo,iso,fd,currentCount,qty));

        try{
            capSequenceBuilder = camDev.createCaptureRequest(camDev.TEMPLATE_STILL_CAPTURE);
            capSequenceBuilder.addTarget(rawImgReader.getSurface());

            capSequenceBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            capSequenceBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expo);
            capSequenceBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            capSequenceBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            capSequenceBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, fd);

            //Log.v("a", String.format("start capture %d sec",(int)(expo/1000000L)));
        }catch(CameraAccessException e){
            e.printStackTrace();
        }

        isCapturing = true;
        capture();

    }
    private void capture(){
        if(sequrnceLength <= currentCount){

            // end of capture sequence
            // save csv & tiff
            File file = new File(activity.getExternalFilesDir(null), "tmp_csv.csv");

            //byte[] tiffBytes = processImg(file.getAbsolutePath());

            //if(tiffBytes == null || tiffBytes.length == 0){
            //    Log.e("a", "failed to convert dng to tiff");
            //    return ;
            //}
            // save tiff
            ContentValues valuesTiff = new ContentValues();
            ContentValues valuesPng = new ContentValues();
            ContentResolver resolver = activity.getContentResolver();
            Uri uriTiff = getUri("Documents/SSA/imgs/" + sequenceName + "/", "stacked.tif", "image/tiff",resolver , valuesTiff);
            Uri uriPng = getUri("Documents/SSA/imgs/" + sequenceName + "/", "stacked.jpg", "image/jpeg",resolver , valuesPng);

            // save tiff
            //ContentValues values = new ContentValues();
            //values.put(MediaStore.MediaColumns.DISPLAY_NAME, "stacked.tif");
            //values.put(MediaStore.MediaColumns.MIME_TYPE, "image/tiff");
            //values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/SSA/imgs/" + sequenceName);

            //Uri uri = activity.getContentResolver().insert(MediaStore.Files.getContentUri("external"), values);

            try{
                if(uriTiff != null && uriPng != null){
                    ParcelFileDescriptor pfdTiff = resolver.openFileDescriptor(uriTiff, "w");
                    ParcelFileDescriptor pfdPng = resolver.openFileDescriptor(uriPng, "w");
                    if(pfdTiff != null && pfdPng != null){
                        Log.d("a_saveImg",saveImg(pfdTiff.getFd(), pfdPng.getFd()));
                        pfdTiff.close();
                        pfdPng.close();

                        valuesTiff.clear();
                        valuesTiff.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        resolver.update(uriTiff, valuesTiff, null, null);
                        valuesPng.clear();
                        valuesPng.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        resolver.update(uriPng, valuesPng, null, null);

                        Log.d("a","saved");

                    }
                    
                }
            }catch(IOException e){
                e.printStackTrace();
            }

            /*
            // save csv
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, "CSV_" + System.currentTimeMillis() + ".csv");
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/SSA/imgs/" + sequenceName + "/");

            resolver = activity.getContentResolver();
            Uri uri = resolver.insert(MediaStore.Files.getContentUri("external"), values);
                    // copy to mediastore
            if(uri != null){
                try(FileInputStream input = new FileInputStream(file)){
                    OutputStream output = activity.getContentResolver().openOutputStream(uri);

                    byte[] buff1 = new byte[1024 * 4];
                    int length;
                    while((length = input.read(buff1)) > 0){
                        output.write(buff1, 0, length);
                    }

                    values.clear();
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    resolver.update(uri, values, null, null);

                    Log.d("a", "csv saved at "+uri.toString());
                    //String[] fileNames = getExternalFilesDir(null).list();
                    //for(int i=0; i<fileNames.length; i++){
                    //    Log.d("a", fileNames[i]);
                    //}
                }catch(IOException e){
                    e.printStackTrace();
                    resolver.delete(uri, null, null);
                }
            }
            */
            indicator.post(new Runnable(){
                @Override
                public void run(){
                    indicator.setText(String.format("Capture Sequence completed!\n%s, \n%d ms, %d, %f\n%d/%d done",sequenceName,expo,iso,fd,currentCount,sequrnceLength));
                    isCapturing = false;
                }
            });
            Log.d("a","capture sequence done !");
        }else{
            indicator.post(new Runnable(){
                @Override
                public void run(){
                    indicator.setText(String.format("Capturing…\n%s, \n%d ms, %d, %f\n%d/%d done",sequenceName,expo,iso,fd,currentCount,sequrnceLength));
                }
            });
            try{
                capSession.capture(capSequenceBuilder.build(), capCallback, backgroundHandler);
                Log.d("a","start capture No." + currentCount);
            }catch(CameraAccessException e){
                e.printStackTrace();
            }
        }
        return ;
    }


    private TotalCaptureResult lastCapResult;

    private final CameraCaptureSession.CaptureCallback capCallback = new CameraCaptureSession.CaptureCallback(){
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result){
            super.onCaptureCompleted(session, request, result);
           
            lastCapResult = result;

        }
    };
    private final ImageReader.OnImageAvailableListener onRawImageAvailableListener = new ImageReader.OnImageAvailableListener(){
        @Override
        public void onImageAvailable(ImageReader reader){
            Log.v("a", "img available");
            Image img = null;

            img = reader.acquireNextImage();

            if(lastCapResult != null){
                Log.d("a","end capture No." + currentCount);
                // left vol. right vol. priority loop speed
                soundPool.play(alarmSound, 1.0f, 1.0f, 0, 0, 1);

                saveDNG(img, lastCapResult);
                Image.Plane plane = img.getPlanes()[0];
                ByteBuffer buff = plane.getBuffer();

                // accumulate with OpenCVc++
                Log.d("a",accumulateImg(buff, plane.getRowStride(), buff.remaining()));
                currentCount++;

                indicator.post(new Runnable(){
                    @Override
                    public void run(){
                        indicator.setText(String.format("Capturing…\n%s, \n%d ms, %d, %f\n%d/%d done",sequenceName,expo,iso,fd,currentCount,sequrnceLength));
                    }
                });

                if(img != null){
                    img.close();
                }

                backgroundHandler.postDelayed(()->capture(), 1000);

            }else{
                Log.d("a", "img is null");
                indicator.post(new Runnable(){
                    @Override
                    public void run(){
                        indicator.setText("obtained NULL IMAGE. Please try again.");
                        capture();
                    }
                });
            }


        }
    };

    private void saveDNG(Image img, TotalCaptureResult result){
        ContentValues values = new ContentValues();
        ContentResolver resolver = activity.getContentResolver();
        Uri uri = getUri("Documents/SSA/imgs/" + sequenceName + "/" , currentCount + ".dng" , "image/x-adobe-dng", resolver , values);
        DngCreator dngCreator = new DngCreator(camCharacteristics, result);
        //ContentResolver resolver = activity.getContentResolver();
        //Uri collection = MediaStore.Files.getContentUri("external");

        //Uri uri = null;
        //String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
        //String[] selectionArgs = new String[]{ currentCount + ".dng" };

        //// search whether same file exsists.
        //try(Cursor c = resolver.query(collection,new String[]{MediaStore.MediaColumns._ID},selection,selectionArgs,null)){
        //    if(c != null && c.moveToFirst()){
        //        long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
        //        // exsists
        //        uri = ContentUris.withAppendedId(collection, id);
        //    }
        //}
        //

        //if(uri == null){
        //    // does not exsists
        //    ContentValues values = new ContentValues();
        //    values.put(MediaStore.MediaColumns.DISPLAY_NAME, currentCount + ".dng");
        //    values.put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng");
        //    values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/SSA/imgs/" + sequenceName);
        //    uri = activity.getContentResolver().insert(MediaStore.Files.getContentUri("external"), values);
        //}

        try{
            if(uri != null){
                try(OutputStream output = activity.getContentResolver().openOutputStream(uri, "wt")){
                    dngCreator.writeImage(output, img);
                    Log.d(TAG, "DNG saved at " + uri.toString());

                    values.clear();
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    /*
                    String[] fileNames = getExternalFilesDir(null).list();
                    for(int i=0; i<fileNames.length; i++){
                        Log.d("a", fileNames[i]);
                    }
                    */
                }
            }else{
                Log.d("a", "uri is null!!!!!!!");
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

    public void setTextureListener(TextureView tv){
        tv.setSurfaceTextureListener(textureListener);
    }
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback(){
        @Override
        public void onOpened(@NonNull CameraDevice cam){
            camDev = cam;
            if(doPreview){
                createCamPreviewSession();

            }
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
            
            if(tv1.isAvailable() && tv2.isAvailable()){
                setup();

                // max resolution
                surface.setDefaultBufferSize(maxW,maxH);
                Log.d("a", String.format("%d,%d",maxW,maxH));
                transformTextures(0);
            }
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
    public void startBackgroundThread(){
        backgroundThread = new HandlerThread("Camerabackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }
    public void stopBackgroundThread(){
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
    public void transformTextures(int zoom){
        //0:noZoom 1:focus 2:pointing
        Matrix matrix1 = new Matrix();
        Matrix matrix2 = new Matrix();

        //float centerX = viewWidth /2f;
        //float centerY = viewHeight /2f;
        //
        float s = 1f;
        if(zoom == 2){
            s = 4f;
        }
        float ratio1 = 3.79668f;
        float ratio2 = 1.05125f;
        float ratio3 = 0.86667f;
        float w = tv1.getWidth();
        float h = tv1.getHeight();

        float[] src = {
            0,0,
            w,0,
            w,h,
            0,h
        };

        float h2 = ratio1*ratio2*w;
        float[] dst = {
            w/2.0F - s*ratio1*w/2,0,
            w/2.0F + s*ratio1*w/2,0,
            w/2.0F + s*w/2,s*h2,
            w/2.0F - s*w/2,s*h2
        };
        matrix1.setPolyToPoly(src, 0, dst, 0, 4);
        //matrix1.postTranslate(s*-(1-ratio3)*w/2, s*-(h2-h));
        matrix1.postTranslate(0, -(s*h2-h));
        //matrix1.postTranslate(0, s*-h*0.9F);
        tv1.setTransform(matrix1);

        float s2 = 15;
        w = tv2.getWidth();
        h = tv2.getHeight();
        src = new float[]{
            0,0,
            w,0,
            w,h,
            0,h
        };
        if(zoom == 1){
            //なぜかRectで移動したズームができないので，zoomingのときは一番うえまで使う
            h2 = h*2;
            dst = new float[]{
                w/2.0f - s2*w/2,0,
                w/2.0f + s2*w/2,0,
                w/2.0f + s2*w/2,h2,
                w/2.0f - s2*w/2,h2
            };
            matrix2.setPolyToPoly(src, 0, dst, 0, 4);
            matrix2.postTranslate(0, 300);
        }else{
            h2 = h*4;
            dst = new float[]{
                w/2.0f - s2*w/2,0,
                w/2.0f + s2*w/2,0,
                w/2.0f + s2*w/2,h2,
                w/2.0f - s2*w/2,h2
            };
            matrix2.setPolyToPoly(src, 0, dst, 0, 4);
            //matrix2.postTranslate(s*-(1-ratio3)*w/2 - s*w*(s2-1)/2, s*-(h2-h)*0.3f);
            matrix2.postTranslate(0, -(h2-h)*0.3f);
        }

        tv2.setTransform(matrix2);
    }
    /**
     * A native method that is implemented by the 'simple_spectroscope' native library,
     * which is packaged with this application.
     */
    public native void prepare(int width, int height);
    public native String accumulateImg(ByteBuffer buff, int rowStride, int bufferSize);
    //public native byte[] processImg(String filepath);
    public native String saveImg(int fdTiff, int fdPng);
}
