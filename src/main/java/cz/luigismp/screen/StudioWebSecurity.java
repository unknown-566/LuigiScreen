package cz.luigismp.screen;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class StudioWebSecurity {

    private final SecureRandom random;
    private final Clock clock;
    private final Map<String, PendingLogin> pending = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    StudioWebSecurity() {
        this(new SecureRandom(), Clock.systemUTC());
    }

    StudioWebSecurity(SecureRandom random, Clock clock) {
        this.random = random;
        this.clock = clock;
    }

    String issueLogin(String actor, Set<String> capabilities, Duration lifetime) {
        cleanup();
        String token = token(32);
        pending.put(token, new PendingLogin(actor, Set.copyOf(capabilities),
                clock.millis() + lifetime.toMillis()));
        return token;
    }

    Session consumeLogin(String token, Duration sessionLifetime) {
        cleanup();
        if (token == null || token.isBlank()) {
            return null;
        }
        PendingLogin login = pending.remove(token);
        if (login == null || login.expiresAtMillis() <= clock.millis()) {
            return null;
        }
        String id = token(32);
        Session session = new Session(id, token(24), login.actor(),
                login.capabilities(), clock.millis() + sessionLifetime.toMillis());
        sessions.put(id, session);
        return session;
    }

    Session authenticate(String id) {
        if (id == null || id.isBlank()) return null;
        Session session = sessions.get(id);
        if (session == null) return null;
        if (session.expiresAtMillis() <= clock.millis()) {
            sessions.remove(id, session);
            return null;
        }
        return session;
    }

    void revoke(String id) {
        if (id != null) sessions.remove(id);
    }

    int revokeActor(String actor) {
        int before = sessions.size();
        sessions.entrySet().removeIf(entry -> entry.getValue().actor().equals(actor));
        pending.entrySet().removeIf(entry -> entry.getValue().actor().equals(actor));
        return before - sessions.size();
    }

    void revokeAll() {
        pending.clear();
        sessions.clear();
    }

    int activeSessions() {
        cleanup();
        return sessions.size();
    }

    private void cleanup() {
        long now = clock.millis();
        pending.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
    }

    private String token(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    record Session(String id, String csrf, String actor, Set<String> capabilities,
                   long expiresAtMillis) {
        boolean can(String capability) {
            return capabilities.contains("*") || capabilities.contains(capability);
        }
    }

    private record PendingLogin(String actor, Set<String> capabilities,
                                long expiresAtMillis) {
    }
}
