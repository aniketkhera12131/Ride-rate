import com.aniketkhera.riderate.OfferParser;

import java.util.Arrays;
import java.util.List;

/** Checks the offer maths against sample screens. Run: javac + java (see the build workflow). */
public class ParserCheck {
    private static int failures = 0;

    public static void main(String[] args) {
        // Lyft screenshot (Oct 2026)
        check("Lyft ride", Arrays.asList(
                "$11.08", "Incl. CA$1.01 bonus", "$41.55/hr est. rate for this ride",
                "4 mins · 1.9 km", "17A Ave NW & 38 St NW, Edmonton",
                "12 mins · 8.5 km", "Ellerslie, Edmonton",
                "Rider is usually ready at pickup", "Nicholas", "5.0", "Lyft", "Accept"),
                11.08, 16, 10.4, 41.55);

        // Lyft with fare and bonus in one text box
        check("Lyft one box", Arrays.asList(
                "$11.08 Incl. CA$1.01 bonus", "$41.55/hr est. rate for this ride",
                "4 mins · 1.9 km", "12 mins · 8.5 km", "Accept"),
                11.08, 16, 10.4, 41.55);

        // Uber screenshot (matched screen), with turn-by-turn banner on top
        check("Uber ride", Arrays.asList(
                "Turn left", "20 m", "7313 106 St NW", "Edmonton, AB T6E 4V7",
                "Matched to trip", "UberX", "$14.99", "4.78",
                "19 mins (9.0 km) away", "7313 106 St NW, Edmonton, AB T6E 4V7, Canada",
                "14 mins (5.0 km) trip", "10830 NW Jasper Ave, Edmonton, AB T5J 2B3, Canada",
                "Start navigation"),
                14.99, 33, 14.0, 27.25);

        // Uber offer with the earnings counter at the top of the screen
        check("Uber + earnings counter", Arrays.asList(
                "$123.45", "Today", "UberX", "Exclusive", "$9.40", "+$2.00 included", "4.91",
                "6 mins (2.1 km) away", "Whyte Ave", "12 mins (6.3 km) trip", "Downtown", "Accept"),
                9.40, 18, 8.4, 31.33);

        // Uber Eats with a single total line
        check("Uber Eats total", Arrays.asList(
                "$203.10", "Delivery", "$8.25", "incl. expected tip",
                "22 min (7.4 km) total", "McDonald's", "Accept"),
                8.25, 22, 7.4, 22.50);

        // Shop & Pay with time and distance in separate boxes
        check("Shop & Pay split", Arrays.asList(
                "Shop & Pay", "$21.60", "Includes expected tip", "14 items",
                "48 min total", "9.6 km total", "Superstore", "Accept"),
                21.60, 48, 9.6, 27.00);

        // Long trip with hours, pickup in metres
        check("Long trip, metres", Arrays.asList(
                "UberX", "$62.30", "3 mins (650 m) away", "Airport",
                "1 hr 5 min (48.2 km) trip", "Accept"),
                62.30, 68, 48.85, 54.97);

        // Whole card read twice (container description + each text)
        check("Duplicate text", Arrays.asList(
                "Ride offer $14.99 19 mins (9.0 km) away 14 mins (5.0 km) trip",
                "$14.99", "19 mins (9.0 km) away", "14 mins (5.0 km) trip", "Accept"),
                14.99, 33, 14.0, 27.25);

        // Red example
        check("Low pay", Arrays.asList(
                "UberX", "$6.10", "11 mins (4.2 km) away", "13 mins (5.0 km) trip", "Accept"),
                6.10, 24, 9.2, 15.25);

        // Screens that are not offers
        none("Home screen", Arrays.asList("$123.45", "Today", "You're online", "Finding trips"));
        none("Map only", Arrays.asList("Turn left", "20 m", "Edmonton"));
        none("Rate only", Arrays.asList("$41.55/hr", "4 mins · 1.9 km"));

        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("All checks passed");
    }

    private static void check(String name, List<String> texts, double fare, double min, double km, double perHour) {
        OfferParser.Offer o = OfferParser.parse(texts);
        boolean ok = o != null
                && Math.abs(o.fare - fare) < 0.001
                && Math.abs(o.minutes - min) < 0.01
                && Math.abs(o.km - km) < 0.06
                && Math.abs(o.perHour() - perHour) < 0.01;
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + "  ->  " + o);
        if (!ok) failures++;
    }

    private static void none(String name, List<String> texts) {
        OfferParser.Offer o = OfferParser.parse(texts);
        boolean ok = o == null;
        System.out.println((ok ? "PASS  " : "FAIL  ") + name + "  ->  " + o);
        if (!ok) failures++;
    }
}
