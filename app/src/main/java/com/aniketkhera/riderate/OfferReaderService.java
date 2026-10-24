package com.aniketkhera.riderate;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads the screen of the Uber Driver and Lyft Driver apps (read only — it never taps anything)
 * and shows the $/hr and $/km badge while an offer is on screen.
 */
public class OfferReaderService extends AccessibilityService {

    static volatile OfferReaderService instance;

    private static final Set<String> APPS = new HashSet<>(Arrays.asList(
            "com.ubercab.driver",       // Uber Driver: rides, Uber Eats, Shop & Pay
            "com.lyft.android.driver"   // Lyft Driver
    ));
    private static final int MAX_NODES = 2500;
    private static final long HIDE_AFTER_MS = 1200;
    private static final long MAX_SHOW_MS = 60_000;

    /** Last few offer-like screens, kept in memory only, for the troubleshooting button. */
    private static final Deque<String> recentScreens = new ArrayDeque<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Badge badge;
    private boolean scanQueued;
    private boolean testing;
    private boolean suppressed;
    private long lastOfferSeenAt;
    private long shownSince;

    private final Runnable scanTask = () -> {
        scanQueued = false;
        scan();
    };

    // While the badge is up, keep checking so it disappears as soon as the offer does.
    private final Runnable watchTask = new Runnable() {
        @Override
        public void run() {
            if (badge == null || !badge.isShowing() || testing) return;
            scan();
            if (badge.isShowing()) handler.postDelayed(this, 700);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        badge = new Badge(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (testing || scanQueued || badge == null) return;
        scanQueued = true;
        handler.postDelayed(scanTask, 200);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void cleanup() {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        if (badge != null) badge.hide();
    }

    private void scan() {
        if (testing) return;
        List<String> texts = readOfferApps();
        OfferParser.Offer offer = texts.isEmpty() ? null : OfferParser.parse(texts);
        if (!texts.isEmpty()) remember(texts, offer);
        long now = SystemClock.uptimeMillis();

        if (offer != null) {
            lastOfferSeenAt = now;
            if (suppressed) return;
            if (!badge.isShowing()) shownSince = now;
            if (now - shownSince > MAX_SHOW_MS) {
                // Same screen for a full minute (e.g. after accepting): stop covering the map.
                suppressed = true;
                badge.hide();
                return;
            }
            boolean wasShowing = badge.isShowing();
            badge.show(offer);
            if (!wasShowing && badge.isShowing()) {
                handler.removeCallbacks(watchTask);
                handler.postDelayed(watchTask, 700);
            }
        } else if (now - lastOfferSeenAt > HIDE_AFTER_MS) {
            suppressed = false;
            badge.hide();
        }
    }

    private List<String> readOfferApps() {
        List<String> out = new ArrayList<>();
        int[] budget = {MAX_NODES};
        boolean found = false;
        List<AccessibilityWindowInfo> windows = null;
        try {
            windows = getWindows();
        } catch (RuntimeException ignored) {
        }
        if (windows != null) {
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo root;
                try {
                    root = w.getRoot();
                } catch (RuntimeException e) {
                    root = null;
                }
                if (root == null) continue;
                CharSequence pkg = root.getPackageName();
                if (pkg == null || !APPS.contains(pkg.toString())) continue;
                found = true;
                walk(root, out, budget, 0);
            }
        }
        if (!found) {
            AccessibilityNodeInfo root = null;
            try {
                root = getRootInActiveWindow();
            } catch (RuntimeException ignored) {
            }
            if (root != null && root.getPackageName() != null
                    && APPS.contains(root.getPackageName().toString())) {
                walk(root, out, budget, 0);
            }
        }
        return out;
    }

    private void walk(AccessibilityNodeInfo node, List<String> out, int[] budget, int depth) {
        if (node == null || budget[0]-- <= 0 || depth > 80) return;
        try {
            if (node.isVisibleToUser()) {
                CharSequence t = node.getText();
                if (t != null && t.length() > 0) out.add(t.toString());
                CharSequence d = node.getContentDescription();
                if (d != null && d.length() > 0 && (t == null || !d.toString().contentEquals(t))) {
                    out.add(d.toString());
                }
            }
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                walk(node.getChild(i), out, budget, depth + 1);
            }
        } catch (RuntimeException ignored) {
            // A screen can change while it is being read; just skip what is gone.
        }
    }

    private static void remember(List<String> texts, OfferParser.Offer offer) {
        String joined = TextUtils.join(" | ", texts);
        String lower = joined.toLowerCase(Locale.US);
        boolean offerLike = offer != null
                || (joined.contains("$") && lower.contains("min") && (lower.contains("km") || lower.contains(" m")));
        if (!offerLike) return;
        if (joined.length() > 4000) joined = joined.substring(0, 4000) + " …";
        String entry = (offer != null ? "READ OK: " + offer : "NOT READ") + "\n" + joined;
        synchronized (recentScreens) {
            String first = recentScreens.peekFirst();
            if (first != null && first.regionMatches(0, entry, 0, Math.min(160, Math.min(first.length(), entry.length())))) {
                recentScreens.removeFirst(); // same screen updating: keep only the latest copy
            }
            recentScreens.addFirst(entry);
            while (recentScreens.size() > 3) recentScreens.removeLast();
        }
    }

    static String lastScreensText() {
        synchronized (recentScreens) {
            return TextUtils.join("\n\n-----\n\n", recentScreens);
        }
    }

    /** Green, yellow and red sample badges, two seconds each. */
    void showTestBadges() {
        if (badge == null) return;
        testing = true;
        handler.removeCallbacks(watchTask);
        handler.removeCallbacks(scanTask);
        scanQueued = false;
        badge.show(new OfferParser.Offer(14.99, 33, 14.0)); // $27.25/hr  green
        handler.postDelayed(() -> badge.show(new OfferParser.Offer(7.50, 20, 6.0)), 2000); // $22.50/hr  yellow
        handler.postDelayed(() -> badge.show(new OfferParser.Offer(6.10, 24, 9.2)), 4000); // $15.25/hr  red
        handler.postDelayed(() -> {
            badge.hide();
            testing = false;
        }, 6500);
    }
}
