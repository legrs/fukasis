// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
package com.example.ssa;

import android.content.Context;
import android.widget.TextView;

import com.example.ssa.R;
import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.utils.MPPointF;

public class CustomMarkerView extends MarkerView {

    private TextView tvContent;

    public CustomMarkerView(Context context, int layoutResource) {
        super(context, layoutResource);
        // XMLのTextViewを見つけておく
        tvContent = findViewById(R.id.tvContent);
    }

    // タップされるたびに呼ばれるメソッド
    @Override
    public void refreshContent(Entry e, Highlight highlight) {
        // e.getX() が波長、e.getY() が強度です
        String text = "wavelength: " + e.getX() + "\nintensity: " + e.getY();
        tvContent.setText(text);

        // 必須：レイアウトのサイズを再計算させる
        super.refreshContent(e, highlight);
    }

    // ポップアップを表示する位置のズレ（オフセット）を調整するメソッド
    @Override
    public MPPointF getOffset() {
        // 指で隠れないように、タップしたポイントの「真上」かつ「中央」に表示する設定
        return new MPPointF(-(getWidth() / 2f), -getHeight() - 25f);
    }
}
