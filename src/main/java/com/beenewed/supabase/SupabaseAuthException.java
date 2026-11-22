package com.beenewed.supabase;

/**
 * Thrown when Supabase authentication (sign in, sign up, or session validation) fails.
 */
public final class SupabaseAuthException extends Exception {
    public SupabaseAuthException(String message) {
        super(message);
    }

    public SupabaseAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
