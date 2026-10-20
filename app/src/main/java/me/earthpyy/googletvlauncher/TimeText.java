package me.earthpyy.googletvlauncher;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Clock and date text shared by the home screen and the screensaver. Main thread only. */
final class TimeText {
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm", Locale.ENGLISH);
    private static final SimpleDateFormat DATE = new SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH);

    private TimeText() {
    }

    static String clock(Date date) {
        // Formatters cache the zone they were created with, so follow timezone changes
        CLOCK.setTimeZone(TimeZone.getDefault());
        return CLOCK.format(date);
    }

    static String date(Date date) {
        DATE.setTimeZone(TimeZone.getDefault());
        return DATE.format(date);
    }
}
