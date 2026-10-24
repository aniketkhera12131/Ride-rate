package com.aniketkhera.riderate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the fare, total minutes and total km out of the text on an
 * Uber / Uber Eats / Shop & Pay / Lyft offer screen.
 *
 * Plain Java on purpose (no Android code), so it can be tested anywhere.
 */
public final class OfferParser {

    /** One offer: fare in dollars, minutes and km for pickup + trip together. */
    public static final class Offer {
        public final double fare;
        public final double minutes;
        public final double km;

        public Offer(double fare, double minutes, double km) {
            this.fare = fare;
            this.minutes = minutes;
            this.km = km;
        }

        public double perHour() {
            return minutes > 0 ? fare * 60.0 / minutes : 0;
        }

        public double perKm() {
            return km > 0 ? fare / km : 0;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "$%.2f, %.0f min, %.1f km -> $%.2f/hr, $%.2f/km",
                    fare, minutes, km, perHour(), perKm());
        }
    }

    /** How many text items after the fare we look for the time and distance. */
    private static final int WINDOW = 12;

    // "12 mins", "1 hr 5 min", "2 h"
    private static final String TIME =
            "(?:(\\d{1,2})\\s*(?:hours?|hrs?|h)\\b\\.?\\s*(?:(\\d{1,2})\\s*(?:minutes?|mins?)\\b\\.?)?"
                    + "|(\\d{1,3})\\s*(?:minutes?|mins?)\\b\\.?)";
    // "8.5 km", "650 m", "3.1 mi"
    private static final String DIST =
            "(\\d{1,4}(?:[.,]\\d{1,2})?)\\s*(km|kilomet(?:er|re)s?|miles?|mi|m)\\b";
    // A time followed closely by a distance: "4 mins · 1.9 km", "19 mins (9.0 km) away"
    private static final Pattern PAIR =
            Pattern.compile(TIME + "[^\\d$]{0,18}?" + DIST, Pattern.CASE_INSENSITIVE);
    private static final Pattern MONEY =
            Pattern.compile("\\$\\s?(\\d{1,4}(?:[.,]\\d{1,2})?)");

    private static final class Money {
        final double value;
        final int pos;
        final int seg;

        Money(double value, int pos, int seg) {
            this.value = value;
            this.pos = pos;
            this.seg = seg;
        }
    }

    private static final class Leg {
        final double minutes;
        final double km;
        final int pos;
        final int seg;
        final boolean total;

        Leg(double minutes, double km, int pos, int seg, boolean total) {
            this.minutes = minutes;
            this.km = km;
            this.pos = pos;
            this.seg = seg;
            this.total = total;
        }
    }

    private OfferParser() {
    }

    /** @param rawTexts every piece of text on screen, in screen order. Returns null if no offer is showing. */
    public static Offer parse(List<String> rawTexts) {
        List<String> segs = new ArrayList<>();
        for (String s : rawTexts) {
            if (s == null) continue;
            String t = s.replace(' ', ' ').replace(' ', ' ').replaceAll("\\s+", " ").trim();
            if (!t.isEmpty()) segs.add(t);
        }
        if (segs.isEmpty()) return null;

        // Join everything so a time and a distance in separate text boxes can still pair up.
        StringBuilder sb = new StringBuilder();
        int[] starts = new int[segs.size()];
        for (int i = 0; i < segs.size(); i++) {
            starts[i] = sb.length();
            sb.append(segs.get(i)).append('\n');
        }
        String all = sb.toString();

        List<Money> monies = findMoney(segs, starts);
        if (monies.isEmpty()) return null;
        List<Leg> legs = findLegs(all, starts);
        if (legs.isEmpty()) return null;

        // Usual layout: fare first, then pickup and trip below it.
        // Pick the dollar amount with the closest time/distance after it
        // (this skips the "today's earnings" counter at the top of Uber).
        Money fare = null;
        int bestGap = Integer.MAX_VALUE;
        for (Money m : monies) {
            Leg first = null;
            for (Leg l : legs) {
                if (l.pos > m.pos) {
                    first = l;
                    break;
                }
            }
            if (first == null) continue;
            int gap = first.seg - m.seg;
            if (gap <= WINDOW && gap <= bestGap) {
                bestGap = gap;
                fare = m;
            }
        }

        List<Leg> chosen = new ArrayList<>();
        if (fare != null) {
            int limit = Integer.MAX_VALUE;
            for (Money m : monies) {
                if (m.pos > fare.pos) {
                    limit = m.pos;
                    break;
                }
            }
            for (Leg l : legs) {
                if (l.pos > fare.pos && l.pos < limit && l.seg - fare.seg <= WINDOW) chosen.add(l);
            }
        } else {
            // Fallback layout: time and distance shown above the fare.
            bestGap = Integer.MAX_VALUE;
            for (Money m : monies) {
                Leg last = null;
                for (Leg l : legs) {
                    if (l.pos < m.pos) last = l;
                }
                if (last == null) continue;
                int gap = m.seg - last.seg;
                if (gap <= WINDOW && gap < bestGap) {
                    bestGap = gap;
                    fare = m;
                }
            }
            if (fare == null) return null;
            int limit = -1;
            for (Money m : monies) {
                if (m.pos < fare.pos) limit = m.pos;
            }
            for (Leg l : legs) {
                if (l.pos < fare.pos && l.pos > limit && fare.seg - l.seg <= WINDOW) chosen.add(l);
            }
        }
        if (chosen.isEmpty()) return null;

        // The same text can be read twice (a box and its label); count each leg once.
        List<Leg> unique = new ArrayList<>();
        for (Leg l : chosen) {
            boolean dup = false;
            for (Leg u : unique) {
                if (Math.abs(u.minutes - l.minutes) < 0.01 && Math.abs(u.km - l.km) < 0.001) {
                    dup = true;
                    break;
                }
            }
            if (!dup) unique.add(l);
        }

        // Deliveries usually show one "total" line; rides show pickup + trip.
        for (Leg l : unique) {
            if (l.total) return new Offer(fare.value, l.minutes, l.km);
        }
        double minutes = 0;
        double km = 0;
        for (int i = 0; i < unique.size() && i < 3; i++) {
            minutes += unique.get(i).minutes;
            km += unique.get(i).km;
        }
        if (minutes <= 0) return null;
        return new Offer(fare.value, minutes, km);
    }

    private static List<Money> findMoney(List<String> segs, int[] starts) {
        List<Money> out = new ArrayList<>();
        for (int i = 0; i < segs.size(); i++) {
            String seg = segs.get(i);
            Matcher m = MONEY.matcher(seg);
            while (m.find()) {
                if (isExtraAmount(segs, i, m.start(), m.end())) continue;
                double v = number(m.group(1));
                if (v < 1 || v > 2000) continue;
                out.add(new Money(v, starts[i] + m.start(), i));
            }
        }
        return out;
    }

    /** True for amounts that are not the fare: bonuses, boosts, tips, or rates like "$41.55/hr". */
    private static boolean isExtraAmount(List<String> segs, int segIndex, int start, int end) {
        String seg = segs.get(segIndex);
        String before = seg.substring(Math.max(0, start - 14), start).toLowerCase(Locale.US);
        if (before.contains("incl") || before.contains("bonus") || before.contains("boost")
                || before.contains("tip") || before.contains("+") || before.contains("surge")) {
            return true;
        }
        if (before.trim().isEmpty() && segIndex > 0) {
            String prev = segs.get(segIndex - 1).toLowerCase(Locale.US).trim();
            if (prev.endsWith("incl.") || prev.endsWith("incl") || prev.endsWith("includes")
                    || prev.endsWith("+") || prev.endsWith("bonus")) {
                return true;
            }
        }
        String after = seg.substring(end).toLowerCase(Locale.US).trim();
        if (after.isEmpty() && segIndex + 1 < segs.size()) {
            after = segs.get(segIndex + 1).toLowerCase(Locale.US).trim();
        }
        return after.startsWith("/") || after.startsWith("per ") || after.startsWith("an hour")
                || after.startsWith("hr") || after.startsWith("an hr");
    }

    private static List<Leg> findLegs(String all, int[] starts) {
        List<Leg> out = new ArrayList<>();
        Matcher p = PAIR.matcher(all);
        while (p.find()) {
            double minutes;
            if (p.group(1) != null) {
                minutes = Integer.parseInt(p.group(1)) * 60 + (p.group(2) != null ? Integer.parseInt(p.group(2)) : 0);
            } else {
                minutes = Integer.parseInt(p.group(3));
            }
            double km = number(p.group(4));
            String unit = p.group(5).toLowerCase(Locale.US);
            if (unit.equals("m")) {
                km = km / 1000.0;
            } else if (unit.startsWith("mi")) {
                km = km * 1.609;
            }
            if (minutes <= 0 || minutes > 600 || km <= 0 || km > 1500) continue;
            int s = p.start();
            int e = p.end();
            String around = all.substring(Math.max(0, s - 10), Math.min(all.length(), e + 14)).toLowerCase(Locale.US);
            out.add(new Leg(minutes, km, s, segmentOf(starts, s), around.contains("total")));
        }
        return out;
    }

    private static int segmentOf(int[] starts, int pos) {
        int seg = 0;
        for (int i = 0; i < starts.length; i++) {
            if (starts[i] <= pos) seg = i;
            else break;
        }
        return seg;
    }

    private static double number(String s) {
        return Double.parseDouble(s.replace(',', '.'));
    }
}
