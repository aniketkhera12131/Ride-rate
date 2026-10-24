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

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
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
    private static final Deque<String> recentOther = new ArrayDeque<>();
    private static final Deque<String> recentIdLike = new ArrayDeque<>();

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
        CharSequence src = event.getPackageName();
        if (src != null && src.toString().equals("com.ubercab.driver")) uberEvents++;
        else if (src != null && src.toString().equals("com.lyft.android.driver")) lyftEvents++;
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
        winInfo.setLength(0);
        nodesSeen = 0;
        hiddenTexts = 0;
        allTexts.clear();
        hiddenList.clear();
        viewIds.clear();
        List<String> texts = readOfferApps();
        OfferParser.Offer offer = texts.isEmpty() ? null : OfferParser.parse(texts);
        boolean fromHidden = false;
        if (offer == null && !hiddenList.isEmpty()) {
            // Some apps report parts of the screen as "not visible" even when they are; try those too.
            offer = OfferParser.parse(allTexts);
            fromHidden = offer != null;
        }
        lastCheck = new SimpleDateFormat("HH:mm:ss", Locale.CANADA).format(new Date())
                + "  windows: " + winInfo;
        if (appFound) {
            remember(texts, hiddenList, viewIds, offer, fromHidden, winInfo.toString(), nodesSeen, hiddenTexts);
        }
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

    // Troubleshooting details for the current scan (memory only).
    private final StringBuilder winInfo = new StringBuilder();
    private int nodesSeen;
    private int hiddenTexts;
    private boolean appFound;
    private final List<String> allTexts = new ArrayList<>();
    private final List<String> hiddenList = new ArrayList<>();
    private final Set<String> viewIds = new java.util.LinkedHashSet<>();

    private List<String> readOfferApps() {
        List<String> out = new ArrayList<>();
        int[] budget = {MAX_NODES};
        appFound = false;
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
                CharSequence pkg = root != null ? root.getPackageName() : null;
                if (winInfo.length() > 0) winInfo.append(", ");
                winInfo.append(shortName(pkg)).append('/').append(windowType(w.getType()));
                if (root == null) winInfo.append("(no content)");
                if (pkg == null || !APPS.contains(pkg.toString())) continue;
                appFound = true;
                int before = nodesSeen;
                walk(root, out, budget, 0);
                winInfo.append('(').append(nodesSeen - before).append(" items)");
            }
        }
        if (!appFound) {
            AccessibilityNodeInfo root = null;
            try {
                root = getRootInActiveWindow();
            } catch (RuntimeException ignored) {
            }
            if (root != null && root.getPackageName() != null
                    && APPS.contains(root.getPackageName().toString())) {
                appFound = true;
                winInfo.append(" + active window");
                walk(root, out, budget, 0);
            }
        }
        return out;
    }

    private void walk(AccessibilityNodeInfo node, List<String> out, int[] budget, int depth) {
        if (node == null || budget[0]-- <= 0 || depth > 80) return;
        nodesSeen++;
        try {
            CharSequence t = node.getText();
            CharSequence d = node.getContentDescription();
            boolean hasT = t != null && t.length() > 0;
            boolean hasD = d != null && d.length() > 0 && (!hasT || !d.toString().contentEquals(t));
            if (node.isVisibleToUser()) {
                if (hasT) out.add(t.toString());
                if (hasD) out.add(d.toString());
            } else if (hasT || hasD) {
                hiddenTexts++;
                if (hasT) hiddenList.add(t.toString());
                if (hasD) hiddenList.add(d.toString());
            }
            if (hasT) allTexts.add(t.toString());
            if (hasD) allTexts.add(d.toString());
            String id = node.getViewIdResourceName();
            if (id != null && viewIds.size() < 80) {
                int slash = id.indexOf('/');
                viewIds.add(slash >= 0 ? id.substring(slash + 1) : id);
            }
            int n = node.getChildCount();
            for (int i = 0; i < n; i++) {
                walk(node.getChild(i), out, budget, depth + 1);
            }
        } catch (RuntimeException ignored) {
            // A screen can change while it is being read; just skip what is gone.
        }
    }

    private static String shortName(CharSequence pkg) {
        if (pkg == null) return "?";
        String p = pkg.toString();
        if (p.equals("com.ubercab.driver")) return "UBER";
        if (p.equals("com.lyft.android.driver")) return "LYFT";
        int dot = p.lastIndexOf('.');
        return dot >= 0 ? p.substring(dot + 1) : p;
    }

    private static String windowType(int type) {
        switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION: return "app";
            case AccessibilityWindowInfo.TYPE_SYSTEM: return "system";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY: return "overlay";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD: return "keyboard";
            default: return "type" + type;
        }
    }

    /** Keeps the last few different screens read from Uber/Lyft, in memory only, for troubleshooting. */
    private static void remember(List<String> texts, List<String> hidden, Set<String> ids,
                                 OfferParser.Offer offer, boolean fromHidden,
                                 String windows, int nodes, int hiddenCount) {
        String joined = TextUtils.join(" | ", texts);
        String hiddenJoined = TextUtils.join(" | ", hidden);
        String idsJoined = TextUtils.join(", ", ids);
        // Screens that look like an offer are kept separately, so home/map updates can't push them out.
        boolean offerLike = offer != null
                || OFFER_HINT.matcher(joined).find()
                || OFFER_HINT.matcher(hiddenJoined).find();
        boolean idLike = !offerLike && OFFER_ID_HINT.matcher(idsJoined).find();
        Deque<String> list = offerLike ? recentScreens : idLike ? recentIdLike : recentOther;
        int keep = offerLike ? 4 : idLike ? 3 : 6;
        String time = new SimpleDateFormat("HH:mm:ss", Locale.CANADA).format(new Date());
        String summary = "items " + nodes + ", texts " + texts.size() + ", hidden " + hiddenCount
                + (offer != null ? ", OFFER READ" : "") + (offerLike && offer == null ? ", offer-like" : "")
                + (idLike ? ", offer-like names" : "")
                + " | " + windows;
        String body = "Visible: " + clip(joined, 900)
                + "\nHidden: " + clip(hiddenJoined, 500)
                + "\nIDs: " + clip(idsJoined, 500);
        synchronized (recentScreens) {
            // Timeline: one short line each time the screen's shape changes.
            String lastLine = timeline.peekFirst();
            if (lastLine == null || !lastLine.endsWith("  " + summary)) {
                timeline.addFirst(time + "  " + summary);
                while (timeline.size() > 30) timeline.removeLast();
            }
            String last = list.peekFirst();
            if (last != null && last.endsWith("\n" + body)) return; // same screen as last time
            String entry = time + "  " + (offer != null ? "READ OK" + (fromHidden ? " (hidden texts)" : "") + ": " + offer
                    : offerLike ? "offer-like, not read" : "no offer found")
                    + "\nWindows: " + windows
                    + "\nItems read: " + nodes + ", texts: " + texts.size() + ", hidden texts: " + hiddenCount
                    + "\n" + body;
            list.addFirst(entry);
            while (list.size() > keep) list.removeLast();
        }
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) + " …" : s;
    }

    private static final java.util.regex.Pattern OFFER_ID_HINT = java.util.regex.Pattern.compile(
            "(?i)(offer|dispatch|fare|accept|match|request|pulse)");
    private static final java.util.regex.Pattern OFFER_HINT = java.util.regex.Pattern.compile(
            "(?i)(\\d\\s?km\\b|\\baccept\\b|\\baway\\b|\\bmatched\\b|\\bper hour\\b|/hr\\b)");
    private static final Deque<String> timeline = new ArrayDeque<>();

    private static volatile long uberEvents;
    private static volatile long lyftEvents;
    private static volatile String lastCheck = "none yet";

    static String lastScreensText() {
        String header = "Ride Rate " + BuildConfig.VERSION_NAME
                + "\nScreen updates seen: Uber " + uberEvents + ", Lyft " + lyftEvents
                + "\nLast check: " + lastCheck;
        synchronized (recentScreens) {
            return header
                    + "\n\n=== Offer-like screens ===\n\n"
                    + (recentScreens.isEmpty() ? "none" : TextUtils.join("\n\n-----\n\n", recentScreens))
                    + "\n\n=== Screens with offer-like view names ===\n\n"
                    + (recentIdLike.isEmpty() ? "none" : TextUtils.join("\n\n-----\n\n", recentIdLike))
                    + "\n\n=== Timeline (newest first) ===\n\n"
                    + (timeline.isEmpty() ? "none" : TextUtils.join("\n", timeline))
                    + "\n\n=== Latest other screens ===\n\n"
                    + (recentOther.isEmpty() ? "none" : TextUtils.join("\n\n-----\n\n", recentOther));
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
