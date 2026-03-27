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

import android.content.ContentResolver;
import android.provider.MediaStore;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.IOException;

public class CalibActivity extends AppCompatActivity{

    private ImageView iv;
    private EditText path_et1; //et=EditText
    private EditText path_et2; //et=EditText

    private ActivityCalibBinding binding;
    private Activity activity = this;

    int[] pos = {0,0};
    float scale = 0.3F;
    float imgWidth ;
    float imgHeight ;
    float dispWidth ;
    float dispHeight;
    float fol;
    float[] t = {0,0,0,0};
    float[] c = {0,0,0,0};
    SeekBar[] sb;
    TextView[] tv;
    FrameLayout[] line;

    private void changesb(int j, int i){
        tv[j].setText("" + i);
        c[j] = (imgWidth - (i));
        line[j].setX(dispWidth+(-imgWidth + c[j])*scale);
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
        //SeekBar sb3 = binding.sb3;
        //TextView t3 = binding.t3;
        //SeekBar sb4 = binding.sb4;
        //TextView t4 = binding.t4;
        //SeekBar sb5 = binding.sb5;
        //TextView t5 = binding.t5;
        FrameLayout l1 = binding.l1;
        line = new FrameLayout[]{binding.l2,binding.l3,binding.l4,binding.l5};
        iv = binding.iv;
        iv.setScaleType(ImageView.ScaleType.MATRIX);

        
        path_et1 = binding.input1;
        path_et2 = binding.input2;
        openBtn.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                ContentResolver resolver = getContentResolver();
                Uri collection = MediaStore.Files.getContentUri("external");
                Uri uri = null;

                String filepath = "Documents/SSA/imgs/" + path_et1.getText().toString() + "/";
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
                }

            }
        });
        exportBtn.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                ContentResolver resolver = activity.getContentResolver();

                ContentValues valuesCsv = new ContentValues();
                Uri uriCsv = Cam.getUri(activity,"Documents/SSA/csv/calibdata/", path_et2.getText().toString() + "csv", "text/csv",resolver , valuesCsv);

                if(uriCsv != null){
                    try(OutputStream output = activity.getContentResolver().openOutputStream(uriCsv)){
                        String dat = String.format("%d,%d,%d,%d\n%d,%d,%d,%d",t[0],t[1],t[2],t[3],c[0],c[1],c[2],c[3]);

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
                fol = imgWidth - (300+i);
                l1.setX(dispWidth+(-imgWidth + fol)*scale);
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
                Log.d("a","" + i);
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
                Log.d("a","" + i);
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
                Log.d("a","" + i);
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
                Log.d("a","" + i);
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
