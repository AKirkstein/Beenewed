package com.beenewed.supabase;

/**
 * Thrown when Supabase configuration, network transport, or PostgREST operations fail.
 */
public final class SupabaseException extends RuntimeException {
    public SupabaseException(String message) {
        super(message);
    }

    public SupabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
