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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Watches the Uber Driver and Lyft Driver apps (read only — it never taps anything) and shows
 * the $/hr and $/km badge while an offer is on screen.
 *
 * 1. If the offer's text can be read directly, that is used.
 * 2. Uber hides its offer card from screen readers, so when the card appears (Uber's screen
 *    suddenly has almost no readable text) a screenshot is read on the phone instead.
 */
public class OfferReaderService extends AccessibilityService {

    static volatile OfferReaderService instance;

    private static final Set<String> APPS = new HashSet<>(Arrays.asList(
            "com.ubercab.driver",       // Uber Driver: rides, Uber Eats, Shop & Pay
            "com.lyft.android.driver"   // Lyft Driver
    ));
    private static final int MAX_NODES = 2500;
    private static final long HIDE_AFTER_MS = 1200;      // text path: hide this soon after the offer goes
    private static final long IMAGE_HOLD_MS = 3500;      // screenshot path: keep badge through a missed read
    private static final long MAX_SHOW_MS = 60_000;
    private static final long RECHECK_MS = 1000;

    // Readable text that looks like part of an offer (used with a $ amount on screen).
    private static final Pattern OFFER_HINT = Pattern.compile(
            "(?i)(\\d\\s?km\\b|\\baccept\\b|\\baway\\b|\\bmatch\\b|/hr\\b)");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Badge badge;
    private ScreenReader screenReader;
    private boolean scanQueued;
    private boolean testing;
    private boolean suppressed;
    private boolean lookingAtImage;   // an offer card seems to be up but its text is hidden
    private long lastOfferSeenAt;
    private long shownSince;

    private final Runnable scanTask = () -> {
        scanQueued = false;
        scan();
    };

    // While the badge is up or a hidden card is on screen, keep checking about once a second.
    private final Runnable watchTask = new Runnable() {
        @Override
        public void run() {
            if (badge == null || testing) return;
            if (!badge.isShowing() && !lookingAtImage) return;
            scan();
            if (badge.isShowing() || lookingAtImage) handler.postDelayed(this, RECHECK_MS);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        badge = new Badge(this);
        if (ScreenReader.supported()) screenReader = new ScreenReader(this);
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
        hideBadge();
        if (screenReader != null) {
            screenReader.close();
            screenReader = null;
        }
    }

    // ---------------------------------------------------------------- main loop

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
            offer = OfferParser.parse(allTexts);
            fromHidden = offer != null;
        }

        String joined = TextUtils.join(" | ", texts);
        // Uber's offer card hides itself: the whole Uber screen suddenly has (almost) no text.
        boolean cardHidden = appOnTop && texts.size() <= 2 && nodesSeen >= 10;
        boolean looksLikeOffer = appOnTop && joined.contains("$") && OFFER_HINT.matcher(joined).find();
        boolean wantImage = offer == null && (cardHidden || looksLikeOffer);

        lastCheck = stamp() + "  windows: " + winInfo + (wantImage ? "  [reading screenshot]" : "");
        if (appFound) {
            remember(texts, hiddenList, viewIds, offer, fromHidden, winInfo.toString(), nodesSeen, hiddenTexts,
                    wantImage);
        }
        long now = SystemClock.uptimeMillis();

        if (offer != null) {
            lookingAtImage = false;
            showOffer(offer, now);
        } else if (wantImage && screenReader != null) {
            if (!lookingAtImage) {
                lookingAtImage = true;
                handler.removeCallbacks(watchTask);
                handler.postDelayed(watchTask, RECHECK_MS);
            }
            screenReader.request(this::onImageRead);
            if (badge.isShowing() && now - lastOfferSeenAt > IMAGE_HOLD_MS) hideBadge();
        } else {
            lookingAtImage = false;
            if (now - lastOfferSeenAt > HIDE_AFTER_MS) {
                suppressed = false;
                hideBadge();
            }
        }
    }

    private void onImageRead(List<String> lines, OfferParser.Offer offer, String error) {
        boolean gone = OfferParser.isGone(lines);
        rememberShot(lines, offer, gone ? "offer taken/expired" : error);
        if (!lookingAtImage || testing || badge == null) return; // the card is already gone
        if (gone) {
            // "Another driver matched to this request" etc.: take the badge down right away.
            hideBadge();
            lastOfferSeenAt = 0;
            return;
        }
        if (offer == null) return;
        OfferParser.Offer cur = currentOffer;
        if (cur != null && badge.isShowing() && Math.abs(cur.fare - offer.fare) < 0.005
                && offer.legs < cur.legs) {
            return; // same offer, but this read missed a line: keep the better reading
        }
        showOffer(offer, SystemClock.uptimeMillis());
    }

    private OfferParser.Offer currentOffer;

    private void hideBadge() {
        currentOffer = null;
        if (badge != null) badge.hide();
    }

    private void showOffer(OfferParser.Offer offer, long now) {
        lastOfferSeenAt = now;
        if (suppressed) return;
        if (!badge.isShowing()) shownSince = now;
        if (now - shownSince > MAX_SHOW_MS) {
            // Same screen for a full minute (e.g. after accepting): stop covering the map.
            suppressed = true;
            hideBadge();
            return;
        }
        boolean wasShowing = badge.isShowing();
        currentOffer = offer;
        badge.show(offer);
        if (!wasShowing && badge.isShowing()) {
            handler.removeCallbacks(watchTask);
            handler.postDelayed(watchTask, RECHECK_MS);
        }
    }

    // ---------------------------------------------------------------- reading screen text

    // Details for the current scan (memory only).
    private final StringBuilder winInfo = new StringBuilder();
    private int nodesSeen;
    private int hiddenTexts;
    private boolean appFound;
    private boolean appOnTop;
    private final List<String> allTexts = new ArrayList<>();
    private final List<String> hiddenList = new ArrayList<>();
    private final Set<String> viewIds = new LinkedHashSet<>();

    private List<String> readOfferApps() {
        List<String> out = new ArrayList<>();
        int[] budget = {MAX_NODES};
        appFound = false;
        appOnTop = false;
        boolean sawAppWindow = false;
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
                boolean ours = pkg != null && APPS.contains(pkg.toString());
                if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && !sawAppWindow) {
                    sawAppWindow = true;            // windows come top-most first
                    appOnTop = ours;
                }
                if (winInfo.length() > 0) winInfo.append(", ");
                winInfo.append(shortName(pkg)).append('/').append(windowType(w.getType()));
                if (root == null) winInfo.append("(no content)");
                if (!ours) continue;
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
                appOnTop = true;
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

    // ---------------------------------------------------------------- troubleshooting (memory only)

    private static final Object LOCK = new Object();
    private static final Deque<String> recentShots = new ArrayDeque<>();
    private static final Deque<String> recentOffers = new ArrayDeque<>();
    private static final Deque<String> recentOther = new ArrayDeque<>();
    private static final Deque<String> timeline = new ArrayDeque<>();
    private static volatile long uberEvents;
    private static volatile long lyftEvents;
    private static volatile long shotsRead;
    private static volatile long shotsWithOffer;
    private static volatile String lastCheck = "none yet";

    private static String stamp() {
        return new SimpleDateFormat("HH:mm:ss", Locale.CANADA).format(new Date());
    }

    private static void rememberShot(List<String> lines, OfferParser.Offer offer, String error) {
        shotsRead++;
        if (offer != null) shotsWithOffer++;
        String entry = stamp() + "  " + (offer != null ? "READ OK: " + offer
                : error != null ? error : "no offer found in screenshot")
                + "\nText: " + clip(TextUtils.join(" | ", lines), 700);
        synchronized (LOCK) {
            String last = recentShots.peekFirst();
            if (last != null && last.substring(last.indexOf("  ")).equals(entry.substring(entry.indexOf("  ")))) {
                recentShots.removeFirst(); // same result as last time: keep just the newest
            }
            recentShots.addFirst(entry);
            while (recentShots.size() > 5) recentShots.removeLast();
        }
    }

    private static void remember(List<String> texts, List<String> hidden, Set<String> ids,
                                 OfferParser.Offer offer, boolean fromHidden,
                                 String windows, int nodes, int hiddenCount, boolean wantImage) {
        String joined = TextUtils.join(" | ", texts);
        boolean offerLike = offer != null || wantImage;
        Deque<String> list = offerLike ? recentOffers : recentOther;
        String time = stamp();
        String summary = "items " + nodes + ", texts " + texts.size() + ", hidden " + hiddenCount
                + (offer != null ? ", OFFER READ" : "") + (wantImage ? ", screenshot" : "")
                + " | " + windows;
        String body = "Visible: " + clip(joined, 900)
                + "\nHidden: " + clip(TextUtils.join(" | ", hidden), 400)
                + "\nIDs: " + clip(TextUtils.join(", ", ids), 400);
        synchronized (LOCK) {
            String lastLine = timeline.peekFirst();
            if (lastLine == null || !lastLine.endsWith("  " + summary)) {
                timeline.addFirst(time + "  " + summary);
                while (timeline.size() > 30) timeline.removeLast();
            }
            String last = list.peekFirst();
            if (last != null && last.endsWith("\n" + body)) return; // same screen as last time
            String entry = time + "  " + (offer != null ? "READ OK" + (fromHidden ? " (hidden texts)" : "") + ": " + offer
                    : wantImage ? "text hidden, using screenshot" : "no offer found")
                    + "\nWindows: " + windows
                    + "\n" + body;
            list.addFirst(entry);
            while (list.size() > 3) list.removeLast();
        }
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) + " …" : s;
    }

    static String lastScreensText() {
        String header = "Ride Rate " + BuildConfig.VERSION_NAME
                + "\nScreenshot reading: " + (ScreenReader.supported() ? "available" : "needs Android 11+")
                + "\nScreen updates seen: Uber " + uberEvents + ", Lyft " + lyftEvents
                + "\nScreenshots read: " + shotsRead + ", with an offer: " + shotsWithOffer
                + "\nLast check: " + lastCheck;
        synchronized (LOCK) {
            return header
                    + "\n\n=== Screenshot reads ===\n\n"
                    + (recentShots.isEmpty() ? "none" : TextUtils.join("\n\n-----\n\n", recentShots))
                    + "\n\n=== Offer screens ===\n\n"
                    + (recentOffers.isEmpty() ? "none" : TextUtils.join("\n\n-----\n\n", recentOffers))
                    + "\n\n=== Timeline (newest first) ===\n\n"
                    + (timeline.isEmpty() ? "none" : TextUtils.join("\n", timeline))
                    + "\n\n=== Latest other screens ===\n\n"
                    + (recentOther.isEmpty() ? "none" : TextUtils.join("\n\n-----\n\n", recentOther));
        }
    }

    // ---------------------------------------------------------------- test badge

    /** Green, yellow and red sample badges, two seconds each. */
    void showTestBadges() {
        if (badge == null) return;
        testing = true;
        lookingAtImage = false;
        handler.removeCallbacks(watchTask);
        handler.removeCallbacks(scanTask);
        scanQueued = false;
        badge.show(new OfferParser.Offer(14.99, 33, 14.0)); // $27.25/hr  green
        handler.postDelayed(() -> badge.show(new OfferParser.Offer(7.50, 20, 6.0)), 2000); // $22.50/hr  yellow
        handler.postDelayed(() -> badge.show(new OfferParser.Offer(6.10, 24, 9.2)), 4000); // $15.25/hr  red
        handler.postDelayed(() -> {
            hideBadge();
            testing = false;
        }, 6500);
    }
}
