package com.aniketkhera.riderate;

import android.accessibilityservice.AccessibilityService;
import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Takes a screenshot (through the accessibility permission) and reads the offer card's text
 * on the phone with Google's on-device text reader. Nothing is saved or sent anywhere.
 * Needs Android 11 or newer.
 */
@TargetApi(30)
final class ScreenReader {

    interface Result {
        /** Called on the main thread. lines is what was read; offer is null if none was found. */
        void onRead(List<String> lines, OfferParser.Offer offer, String error);
    }

    /** Only the lower part of the screen holds the offer card (this also skips our own badge). */
    private static final float CROP_FROM = 0.40f;
    private static final long MIN_INTERVAL_MS = 1000;

    private final AccessibilityService service;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private volatile boolean busy;
    private long lastShotAt;

    ScreenReader(AccessibilityService service) {
        this.service = service;
    }

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 30;
    }

    /** Takes and reads a screenshot unless one is already in progress or was taken too recently. */
    boolean request(Result result) {
        if (!supported() || busy) return false;
        long now = SystemClock.uptimeMillis();
        if (now - lastShotAt < MIN_INTERVAL_MS) return false;
        lastShotAt = now;
        busy = true;
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, worker,
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult shot) {
                            read(shot, result);
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            finish(result, Collections.emptyList(), null, "screenshot failed: " + errorName(errorCode));
                        }
                    });
        } catch (RuntimeException e) {
            finish(result, Collections.emptyList(), null, "screenshot error: " + e.getClass().getSimpleName());
        }
        return true;
    }

    private void read(AccessibilityService.ScreenshotResult shot, Result result) {
        Bitmap crop;
        try (HardwareBuffer hb = shot.getHardwareBuffer()) {
            Bitmap wrapped = Bitmap.wrapHardwareBuffer(hb, shot.getColorSpace());
            if (wrapped == null) {
                finish(result, Collections.emptyList(), null, "could not open screenshot");
                return;
            }
            Bitmap soft = wrapped.copy(Bitmap.Config.ARGB_8888, false);
            wrapped.recycle();
            if (soft == null) {
                finish(result, Collections.emptyList(), null, "could not copy screenshot");
                return;
            }
            int top = (int) (soft.getHeight() * CROP_FROM);
            crop = Bitmap.createBitmap(soft, 0, top, soft.getWidth(), soft.getHeight() - top);
            if (crop != soft) soft.recycle();
        } catch (RuntimeException e) {
            finish(result, Collections.emptyList(), null, "screenshot error: " + e.getClass().getSimpleName());
            return;
        }

        final Bitmap image = crop;
        recognizer.process(InputImage.fromBitmap(image, 0))
                .addOnSuccessListener(worker, text -> {
                    List<String> lines = linesInOrder(text);
                    image.recycle();
                    finish(result, lines, OfferParser.parseImageLines(lines), null);
                })
                .addOnFailureListener(worker, e -> {
                    image.recycle();
                    finish(result, Collections.emptyList(), null, "text reader failed: " + e.getClass().getSimpleName());
                });
    }

    /** All lines of text, top to bottom, left to right. */
    private static List<String> linesInOrder(Text text) {
        List<Text.Line> all = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line l : block.getLines()) {
                if (l.getBoundingBox() != null) all.add(l);
            }
        }
        Collections.sort(all, (a, b) -> Integer.compare(a.getBoundingBox().top, b.getBoundingBox().top));
        // Group lines whose tops are close into one row, then read each row left to right.
        List<String> out = new ArrayList<>();
        List<Text.Line> row = new ArrayList<>();
        int rowTop = 0;
        int rowHeight = 0;
        for (Text.Line l : all) {
            Rect r = l.getBoundingBox();
            if (!row.isEmpty() && r.top - rowTop > Math.max(4, rowHeight / 2)) {
                flushRow(row, out);
            }
            if (row.isEmpty()) {
                rowTop = r.top;
                rowHeight = r.height();
            }
            row.add(l);
        }
        flushRow(row, out);
        return out;
    }

    private static void flushRow(List<Text.Line> row, List<String> out) {
        Collections.sort(row, (a, b) -> Integer.compare(a.getBoundingBox().left, b.getBoundingBox().left));
        for (Text.Line l : row) out.add(l.getText());
        row.clear();
    }

    private void finish(Result result, List<String> lines, OfferParser.Offer offer, String error) {
        busy = false;
        main.post(() -> result.onRead(lines, offer, error));
    }

    void close() {
        try {
            recognizer.close();
        } catch (RuntimeException ignored) {
        }
        worker.shutdown();
    }

    private static String errorName(int code) {
        switch (code) {
            case AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR: return "internal error";
            case AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS: return "no permission (switch Ride Rate off and on in Accessibility)";
            case AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT: return "too soon after the last one";
            case AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY: return "invalid display";
            default: return "code " + code;
        }
    }
}
