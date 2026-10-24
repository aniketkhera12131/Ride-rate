# Ride Rate

Android app that shows **$/hr** and **$/km** the moment a ride or delivery offer appears in the
Uber Driver app (rides, Uber Eats, Shop & Pay) or the Lyft Driver app.

- Both numbers count pickup + trip together (or the "total" line on deliveries).
- Badge colour by $/hr: green $25+, yellow $20 to $24.99, red under $20.
- Read only: it reads the offer text on screen and never taps anything. Taps pass straight through the badge.
- Nothing is saved or sent anywhere.

## Get the app

Every push to `main` builds the app on GitHub. The latest install file is always at
**Releases → latest → RideRate.apk**.

## Install on Android

1. Download `RideRate.apk` on the phone and open it. Allow your browser to install apps if asked.
2. Open Ride Rate → **Turn on reader** → find Ride Rate → switch it on.
3. If the switch is greyed out: App info → ⋮ → **Allow restricted settings**, then step 2 again.
4. Tap **Show test badge** to see the three colours.

## Code

- `OfferParser.java` reads fare, minutes and km from the screen text (plain Java, tested by `tools/ParserCheck.java` on every build).
- `OfferReaderService.java` reads the Uber/Lyft screens and decides when to show or hide the badge.
- `Badge.java` draws the floating badge.
- `MainActivity.java` is the setup screen.
