package cz.luigismp.screen;

import java.util.Map;

record VoteStatus(
        String screen,
        long remainingMillis,
        Map<String, Integer> results,
        int votes
) {
}
