package com.example.ssa;

import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * 画面の中身がステータスバーやナビゲーションバーの下に入り込まないようにする。
 *
 * Android 15 以降 (targetSdk 35 以上) では, アプリの画面はシステムバーの下まで広がる。
 * 何もしないと, 画面の上端や下端にあるボタンがバーと重なって押しにくくなる。
 * それより前の Android では, システムが先に余白を取っているので, ここで足す余白は 0 になる。
 */
final class SystemBars {
    static final int LEFT = 1;
    static final int TOP = 2;
    static final int RIGHT = 4;
    static final int BOTTOM = 8;
    static final int ALL = LEFT | TOP | RIGHT | BOTTOM;

    private SystemBars() {
    }

    /** 画面全体 (root) に, システムバーと切り欠きの分の余白を付ける */
    static void pad(View root) {
        pad(root, root, ALL, false);
    }

    /**
     * target の sides で指定した辺に, システムバーと切り欠きの分の余白を足す。
     * レイアウトに書いてある余白はそのまま残る。root は画面全体の view (ここで余白の大きさを受け取る)。
     *
     * keyboard が true なら, キーボードが出ている間はその分も下に空ける (入力欄までスクロールできるように)。
     * 画像の上に線を重ねている画面では, キーボードの出し入れで配置が動くと線がずれるので false にする。
     */
    static void pad(View root, View target, int sides, boolean keyboard) {
        final int left = target.getPaddingLeft();
        final int top = target.getPaddingTop();
        final int right = target.getPaddingRight();
        final int bottom = target.getPaddingBottom();
        final int types = WindowInsetsCompat.Type.systemBars()
                | WindowInsetsCompat.Type.displayCutout()
                | (keyboard ? WindowInsetsCompat.Type.ime() : 0);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(types);
            target.setPadding(
                    left + ((sides & LEFT) != 0 ? bars.left : 0),
                    top + ((sides & TOP) != 0 ? bars.top : 0),
                    right + ((sides & RIGHT) != 0 ? bars.right : 0),
                    bottom + ((sides & BOTTOM) != 0 ? bars.bottom : 0));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
