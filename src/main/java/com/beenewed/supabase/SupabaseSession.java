package com.beenewed.supabase;

/**
 * Active authenticated Supabase user session stored in memory.
 */
public record SupabaseSession(
        String accessToken,
        String refreshToken,
        String userId,
        String email) {
}
