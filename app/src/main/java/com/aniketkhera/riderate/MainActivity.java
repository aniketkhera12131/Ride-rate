package com.aniketkhera.riderate;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Setup screen: turn on the reader, preview the badge, troubleshoot. */
public class MainActivity extends Activity {

    private static final int GREY = 0xFF555555;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(Color.WHITE);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(36), dp(20), dp(32));
        scroll.addView(col);

        col.addView(text("Ride Rate", 30, true, Color.BLACK));
        col.addView(text("Shows $/hr and $/km on Uber and Lyft offers.", 16, false, GREY));

        status = text("", 18, true, Color.BLACK);
        status.setPadding(0, dp(18), 0, 0);
        col.addView(status);

        col.addView(heading("Step 1: Turn on the reader"));
        col.addView(text("Tap below, find Ride Rate in the list (it can be under \"Installed apps\" "
                + "or \"Downloaded apps\") and switch it on.", 15, false, GREY));
        col.addView(button("Turn on reader", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        col.addView(text("Switch greyed out? Tap \"Open App info\", then the ⋮ menu at the top right, "
                + "then \"Allow restricted settings\". Then do Step 1 again.", 14, false, GREY));
        col.addView(button("Open App info", v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null));
            startActivity(i);
        }));

        col.addView(heading("Step 2: Check the badge"));
        col.addView(text("Shows a green, yellow and red sample for a few seconds.", 15, false, GREY));
        col.addView(button("Show test badge", v -> {
            OfferReaderService svc = OfferReaderService.instance;
            if (svc == null) {
                toast("Turn on the reader first (Step 1).");
            } else {
                svc.showTestBadges();
            }
        }));

        col.addView(heading("Colours"));
        col.addView(text("Green: $25/hr or more\nYellow: $20 to $24.99/hr\nRed: under $20/hr\n\n"
                + "Both numbers count pickup + trip together.", 15, false, Color.BLACK));

        col.addView(heading("What it reads"));
        col.addView(text("Ride Rate reads the text of ride and delivery offers on your screen in the "
                + "Uber Driver and Lyft Driver apps, only to work out these two numbers. Nothing is saved "
                + "or sent anywhere, and it never taps anything for you.", 14, false, GREY));

        col.addView(heading("Numbers not showing?"));
        col.addView(text("Let an offer come in, then open this app and tap below. Copy the text and "
                + "send it to Claude so the reader can be fixed.", 14, false, GREY));
        col.addView(button("Show last screens read", v -> showLastScreens()));

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = isReaderOn();
        status.setText(on ? "Reader: ON" : "Reader: OFF (do Step 1)");
        status.setTextColor(on ? 0xFF1E8E3E : 0xFFD93025);
    }

    private boolean isReaderOn() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(this, OfferReaderService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        for (String s : splitter) {
            if (me.equals(ComponentName.unflattenFromString(s))) return true;
        }
        return false;
    }

    private void showLastScreens() {
        String body = OfferReaderService.lastScreensText();
        if (body.isEmpty()) {
            body = "Nothing read yet. Turn on the reader, open the Uber Driver or Lyft Driver app, "
                    + "wait for an offer, then come back here.";
        }
        final String copy = body;
        TextView tv = text(body, 13, false, Color.BLACK);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Last screens read")
                .setView(sv)
                .setPositiveButton("Copy", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Ride Rate", copy));
                    toast("Copied");
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private TextView heading(String s) {
        TextView t = text(s, 19, true, Color.BLACK);
        t.setPadding(0, dp(26), 0, dp(4));
        return t;
    }

    private TextView text(String s, int sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
