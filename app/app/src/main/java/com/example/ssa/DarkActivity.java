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

import android.content.ContentResolver;
import android.provider.MediaStore;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.IOException;

public class DarkActivity extends AppCompatActivity{

    private EditText path_et1; //et=EditText
    private EditText path_et2; //et=EditText

    private ActivityDarkBinding binding;
    private Activity activity = this;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityDarkBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        //setContentView(R.layout.activity_main);

        // UIs
        Button exportBtn = binding.export;
        
        path_et1 = binding.input1;
        path_et2 = binding.input2;

        exportBtn.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                ContentResolver resolver = getContentResolver();
                Uri collection = MediaStore.Files.getContentUri("external");
                Uri uri1 = null;
                Uri uri2 = null;
                String filepath = "Documents/SSA/imgs/" + path_et1.getText().toString() + "/";
                String filename = "stacked.tif";
                String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
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
                        uri1 = ContentUris.withAppendedId(collection, id);
                        Log.d("a","ありましたよっ！");
                    }else{
                        Log.d("a","ないです");
                    }

                }


                filepath = "Documents/SSA/imgs/" + path_et2.getText().toString() + "/";
                selectionArgs = new String[]{filename, filepath};

                try(Cursor cursor = resolver.query(
                            collection,
                            new String[]{MediaStore.MediaColumns._ID},
                            selection,
                            selectionArgs,
                            null)){
                    if(cursor != null && cursor.moveToFirst()){
                        long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                        // exsists
                        uri2 = ContentUris.withAppendedId(collection, id);
                        Log.d("a","ありましたよっ！");
                    }else{
                        Log.d("a","ないです");
                    }

                }


                ContentValues values = new ContentValues();
                Uri uri3 = Cam.getUri(activity,"Documents/SSA/imgs/" + path_et1.getText().toString() + "/", "darked.tif", "image/tiff",resolver , values);


                try{
                    if(uri1 != null && uri2 != null){
                        ParcelFileDescriptor pfd1 = resolver.openFileDescriptor(uri1, "r");
                        ParcelFileDescriptor pfd2 = resolver.openFileDescriptor(uri2, "r");
                        ParcelFileDescriptor pfd3 = resolver.openFileDescriptor(uri3, "w");

                        if(pfd1 != null && pfd2 != null){
                            int fd1 = pfd1.getFd();
                            int fd2 = pfd2.getFd();
                            int fd3 = pfd3.getFd();
                            processImgs(fd1, fd2, fd3);

                            pfd1.close();
                            pfd2.close();
                            pfd3.close();

                            values.clear();
                            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                            resolver.update(uri3, values, null, null);

                            Log.d("a", "darked");

                        }

                    }
                }catch(IOException e){
                    e.printStackTrace();
                }
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


    public native String processImgs(int fd1, int fd2, int fd3);
}
