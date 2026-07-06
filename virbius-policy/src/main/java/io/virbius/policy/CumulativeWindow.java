package io.virbius.policy;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public final class CumulativeWindow {

    private CumulativeWindow() {}

    public static int windowMinutes(String windowKind, Integer windowMinutes, Integer windowHours) {
        if ("calendar_day".equalsIgnoreCase(windowKind)) {
            return 1440;
        }
        if (windowMinutes != null && windowMinutes > 0) {
            return Math.min(windowMinutes, 10080);
        }
        if (windowHours != null && windowHours > 0) {
            return Math.min(windowHours * 60, 10080);
        }
        throw new IllegalArgumentException("rolling window requires window_minutes or window_hours");
    }

    public static int granularityMinutes(int wMinutes, String windowKind) {
        if ("calendar_day".equalsIgnoreCase(windowKind) || wMinutes >= 1440) {
            return 10;
        }
        return 1;
    }

    public static int bucketCount(int wMinutes, int granularityMin) {
        return (wMinutes + granularityMin - 1) / granularityMin;
    }

    public static long currentSlot(Instant now, int granularityMin) {
        long epochSec = now.getEpochSecond();
        return epochSec / (granularityMin * 60L);
    }

    public static long startSlotCalendarDay(Instant now, ZoneId zone, int granularityMin) {
        ZonedDateTime z = now.atZone(zone);
        ZonedDateTime start = z.toLocalDate().atStartOfDay(zone);
        return start.toEpochSecond() / (granularityMin * 60L);
    }

    public static int ttlSeconds(int wMinutes) {
        return (wMinutes + 120) * 60;
    }
}
