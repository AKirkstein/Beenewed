package com.beenewed.supabase;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SupabaseSessionTest {

    @Test
    void toStringNeverContainsTokens() {
        SupabaseSession s = new SupabaseSession("secret-access-jwt", "secret-refresh", "uid-1", "a@b.co",
                Instant.now(), Instant.now().plusSeconds(3600));
        String text = s.toString();
        assertFalse(text.contains("secret-access-jwt"));
        assertFalse(text.contains("secret-refresh"));
        assertTrue(text.contains("redacted"));
        assertTrue(text.contains("uid-1"));
    }

    @Test
    void expiryChecks() {
        Instant now = Instant.now();
        SupabaseSession fresh = new SupabaseSession("a", "r", "u", "e", now, now.plusSeconds(3600));
        assertFalse(fresh.isExpired());
        assertFalse(fresh.expiresWithin(Duration.ofSeconds(60)));

        SupabaseSession soon = new SupabaseSession("a", "r", "u", "e", now, now.plusSeconds(30));
        assertFalse(soon.isExpired());
        assertTrue(soon.expiresWithin(Duration.ofSeconds(60)));

        SupabaseSession expired = new SupabaseSession("a", "r", "u", "e", now, now.minusSeconds(1));
        assertTrue(expired.isExpired());

        SupabaseSession unknown = new SupabaseSession("a", null, "u", "e");
        assertFalse(unknown.isExpired());
        assertFalse(unknown.expiresWithin(Duration.ofSeconds(60)));
        assertFalse(unknown.hasRefreshToken());
    }
}
