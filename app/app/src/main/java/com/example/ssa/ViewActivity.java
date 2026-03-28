package com.example.ssa;

import android.graphics.Color;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import com.example.ssa.databinding.ActivityViewBinding;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
// MPAndroidChart
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.components.Legend;

import androidx.activity.result.ActivityResultLauncher;
import android.content.Intent;
import androidx.activity.result.contract.ActivityResultContracts;

import android.net.Uri;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import android.app.Activity;
import android.content.ContentValues;
import android.content.ContentUris;
import android.util.Log;
import android.view.View;
import android.widget.Button;

public class ViewActivity extends AppCompatActivity {

    private ActivityViewBinding binding;
    private LineChart lineChart;

    private final ActivityResultLauncher<Intent> csvPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        // ユーザーが選択したファイルのURIを取得できたら、読み込み処理へ渡す
                        readCsvAndDrawChart(uri);
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityViewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        lineChart = binding.lineChart;

        // 先ほど作ったクラスをインスタンス化
        CustomMarkerView marker = new CustomMarkerView(this, R.layout.custom_marker_view);
        
        // グラフの外枠に合わせてマーカーの描画を調整する設定
        marker.setChartView(lineChart);
        
        // グラフにマーカーをセット！
        lineChart.setMarker(marker);

        Button openBtn = binding.open;
        openBtn.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                
                // MIMEタイプの設定（AndroidはCSVの判定が端末によってブレるため、少し広めに指定するのがコツです）
                intent.setType("*/*");
                String[] mimeTypes = {"text/csv", "text/comma-separated-values", "application/csv"};
                intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);

                // 用意しておいたランチャーを使って画面を起動
                csvPickerLauncher.launch(intent);
            }
        });

    }

    // URIからCSVを読み込み、グラフ用のデータリストを作成する
    private void readCsvAndDrawChart(Uri uri) {
        // グラフのデータポイントを入れるリスト
        List<Entry> entries = new ArrayList<>();

        try (InputStream inputStream = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

            String line;
            while ((line = reader.readLine()) != null) {
                // カンマで分割 (例: "400.5, 12500" のような行を想定)
                String[] columns = line.split(",");
                
                if (columns.length >= 2) {
                    try {
                        // X軸（波長）とY軸（強度）を数値(Float)に変換
                        float x = Float.parseFloat(columns[0].trim());
                        float y = Float.parseFloat(columns[1].trim());
                        entries.add(new Entry(x, y));
                    } catch (NumberFormatException e) {
                        // 1行目が「Wavelength,Intensity」などの文字列ヘッダーだった場合は
                        // エラーになるため、ここでキャッチしてスキップします
                        Log.d("CSV_PARSE", "数値以外の行をスキップしました: " + line);
                    }
                }
            }

            // 読み込みが完了したら、グラフを描画するメソッドを呼ぶ
            displayChart(entries);

        } catch (IOException e) {
            Log.e("CSV_READ", "読み込みエラー: ", e);
        }
    }

    // 抽出したデータを使ってグラフを画面に表示する
    private void displayChart(List<Entry> entries) {
        // データセットを作成（"Spectrum"は凡例の表示名）
        LineDataSet dataSet = new LineDataSet(entries, "Spectrum");

        // 💡 スペクトル描画のための重要なカスタマイズ
        dataSet.setColor(Color.BLUE); // 線の色
        dataSet.setLineWidth(1.5f); // 線の太さ
        dataSet.setDrawCircles(false); // ★重要: ポイントごとの丸印を消す（データ数が多いと真っ黒に潰れるため）
        dataSet.setDrawValues(false);  // ★重要: 数値テキストを消す（同上）


        LineData lineData = new LineData(dataSet);
        lineChart.setData(lineData);

        // ピンチズームなどはデフォルトで有効ですが、X軸だけ下部に表示する設定
        XAxis xAxis = lineChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(Color.WHITE);
        lineChart.getAxisLeft().setTextColor(Color.WHITE);

        lineChart.getLegend().setTextColor(Color.WHITE);

        // グラフを更新
        lineChart.invalidate();
    }
}
