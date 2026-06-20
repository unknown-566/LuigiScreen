package cz.luigismp.screen;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioWebSecurityTest {

    @Test
    void loginLinksAreOneTimeAndBecomeScopedSessions() {
        StudioWebSecurity security = new StudioWebSecurity(
                new SecureRandom(new byte[]{1, 2, 3}), Clock.systemUTC());
        String login = security.issueLogin("Admin", Set.of("screens", "control"),
                Duration.ofMinutes(5));

        StudioWebSecurity.Session session = security.consumeLogin(login, Duration.ofHours(8));

        assertNotNull(session);
        assertEquals("Admin", session.actor());
        assertTrue(session.can("screens"));
        assertFalse(session.can("configuration"));
        assertNotNull(security.authenticate(session.id()));
        assertNull(security.consumeLogin(login, Duration.ofHours(8)));
        assertNull(security.consumeLogin(null, Duration.ofHours(8)));
    }

    @Test
    void expiredLoginsAndRevokedSessionsAreRejected() {
        MutableClock clock = new MutableClock();
        StudioWebSecurity security = new StudioWebSecurity(new SecureRandom(), clock);
        String login = security.issueLogin("Admin", Set.of("*"), Duration.ofSeconds(2));
        clock.advance(Duration.ofSeconds(3));
        assertNull(security.consumeLogin(login, Duration.ofHours(1)));

        String fresh = security.issueLogin("Admin", Set.of("*"), Duration.ofMinutes(1));
        StudioWebSecurity.Session session = security.consumeLogin(fresh, Duration.ofHours(1));
        assertNotNull(session);
        assertEquals(1, security.revokeActor("Admin"));
        assertNull(security.authenticate(session.id()));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-06-20T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
