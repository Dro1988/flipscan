package com.droapps.flipscan;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Tiny programmatic-UI helpers shared by the activities. */
public class Ui {

    public static final int BG = 0xFF101418;
    public static final int CARD = 0xFF1B2129;
    public static final int ACCENT = 0xFF35C759;
    public static final int WARN = 0xFFFFB340;
    public static final int BAD = 0xFFFF5A52;
    public static final int TEXT_HI = 0xFFF2F5F7;
    public static final int TEXT_LO = 0xFF9AA7B4;

    public static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static String money(double v) {
        return String.format(Locale.US, "$%.2f", v);
    }

    public static TextView text(Activity a, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    public static Button button(Activity a, String s, int bgColor, int textColor, float sp) {
        Button b = new Button(a);
        b.setText(s);
        b.setTextSize(sp);
        b.setTextColor(textColor);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(a, 12));
        b.setBackground(bg);
        b.setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 12));
        return b;
    }

    public static LinearLayout.LayoutParams lp(Activity a, int w, int h,
                                                 int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(a, l), dp(a, t), dp(a, r), dp(a, b));
        return p;
    }

    public static View card(Activity a, LinearLayout inner) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(a, 14));
        card.setBackground(bg);
        card.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14));
        card.addView(inner);
        return card;
    }

    public static TextView center(TextView t) {
        t.setGravity(Gravity.CENTER);
        return t;
    }
}
