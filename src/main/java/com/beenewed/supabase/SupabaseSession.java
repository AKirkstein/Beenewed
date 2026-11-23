package com.beenewed.supabase;

import java.time.Duration;
import java.time.Instant;

/**
 * Active authenticated Supabase user session stored in memory only (never persisted to disk).
 *
 * @param signedInAt when the user originally signed in (preserved across token refreshes)
 * @param expiresAt  when the current access token expires, or {@code null} if unknown
 */
public record SupabaseSession(
        String accessToken,
        String refreshToken,
        String userId,
        String email,
        Instant signedInAt,
        Instant expiresAt) {

    public SupabaseSession(String accessToken, String refreshToken, String userId, String email) {
        this(accessToken, refreshToken, userId, email, Instant.now(), null);
    }

    public boolean isExpired() {
        return expiresAt != null && !Instant.now().isBefore(expiresAt);
    }

    /** True if the access token expires within {@code margin} (or is already expired). */
    public boolean expiresWithin(Duration margin) {
        return expiresAt != null && !Instant.now().plus(margin).isBefore(expiresAt);
    }

    public boolean hasRefreshToken() {
        return refreshToken != null && !refreshToken.isBlank();
    }

    /** Never expose tokens through logs, exception messages, or debuggers' toString views. */
    @Override
    public String toString() {
        return "SupabaseSession[userId=" + userId
                + ", email=" + email
                + ", signedInAt=" + signedInAt
                + ", expiresAt=" + expiresAt
                + ", tokens=<redacted>]";
    }
}
