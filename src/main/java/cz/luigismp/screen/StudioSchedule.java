package cz.luigismp.screen;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Set;

record StudioSchedule(
        String id,
        boolean enabled,
        Set<DayOfWeek> days,
        LocalTime time,
        String target,
        String action,
        String value,
        int priority,
        String conflict
) {
}
