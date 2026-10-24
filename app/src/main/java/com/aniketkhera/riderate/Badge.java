package com.aniketkhera.riderate;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** The floating $/hr and $/km badge. Taps go straight through it, so it never blocks Accept. */
final class Badge {

    static final double GREEN_FROM = 25.0;   // $25/hr or more
    static final double YELLOW_FROM = 20.0;  // $20 to $24.99/hr; below that is red

    private static final int GREEN = 0xFF1E8E3E;
    private static final int YELLOW = 0xFFF9C80E;
    private static final int RED = 0xFFD93025;

    private final Context ctx;
    private final WindowManager wm;
    private LinearLayout box;
    private TextView hourly;
    private TextView perKm;
    private GradientDrawable bg;
    private boolean showing;

    Badge(Context ctx) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean isShowing() {
        return showing;
    }

    void show(OfferParser.Offer offer) {
        build();
        double hr = cents(offer.perHour());
        int color = hr >= GREEN_FROM ? GREEN : hr >= YELLOW_FROM ? YELLOW : RED;
        int text = color == YELLOW ? Color.BLACK : Color.WHITE;
        bg.setColor(color);
        hourly.setTextColor(text);
        perKm.setTextColor(text);
        hourly.setText(String.format(Locale.CANADA, "$%.2f/hr", hr));
        perKm.setText(offer.km > 0
                ? String.format(Locale.CANADA, "$%.2f/km", cents(offer.perKm()))
                : "— /km");
        if (!showing) {
            try {
                wm.addView(box, layoutParams());
                showing = true;
            } catch (RuntimeException e) {
                showing = false;
            }
        }
    }

    void hide() {
        if (!showing) return;
        try {
            wm.removeView(box);
        } catch (RuntimeException ignored) {
        }
        showing = false;
    }

    private WindowManager.LayoutParams layoutParams() {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        // Upper part of the map: below Uber's turn-by-turn banner, above the offer card.
        p.y = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.28f);
        return p;
    }

    private void build() {
        if (box != null) return;
        box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(2), 0x40000000);
        box.setBackground(bg);
        box.setPadding(dp(24), dp(10), dp(24), dp(12));
        hourly = label(32);
        perKm = label(24);
        box.addView(hourly);
        box.addView(perKm);
    }

    private TextView label(int sp) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setIncludeFontPadding(false);
        return t;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    private static double cents(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
