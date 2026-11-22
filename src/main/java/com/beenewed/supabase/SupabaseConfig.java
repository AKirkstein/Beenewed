package com.beenewed.supabase;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Loads Supabase project credentials from environment variables or a local
 * {@code supabase.properties} file.
 */
public final class SupabaseConfig {
    public static final String ENV_URL = "SUPABASE_URL";
    public static final String ENV_ANON_KEY = "SUPABASE_ANON_KEY";
    public static final String PROPERTIES_FILE = "supabase.properties";

    private final String url;
    private final String anonKey;

    public SupabaseConfig(String url, String anonKey) {
        if (url == null || url.isBlank()) {
            throw new SupabaseException(missingConfigMessage("SUPABASE_URL"));
        }
        if (anonKey == null || anonKey.isBlank()) {
            throw new SupabaseException(missingConfigMessage("SUPABASE_ANON_KEY"));
        }
        String normalizedUrl = url.trim();
        while (normalizedUrl.endsWith("/")) {
            normalizedUrl = normalizedUrl.substring(0, normalizedUrl.length() - 1);
        }
        String normalizedKey = anonKey.trim();
        if (normalizedUrl.contains("your-project-ref") || normalizedKey.equals("your-supabase-anon-key")) {
            throw new SupabaseException(
                    "Supabase credentials still contain placeholder values. "
                            + "Update supabase.properties or set SUPABASE_URL and SUPABASE_ANON_KEY.");
        }
        this.url = normalizedUrl;
        this.anonKey = normalizedKey;
    }

    public static SupabaseConfig load() {
        Properties props = loadProperties();
        String url = firstNonBlank(
                System.getenv(ENV_URL),
                props.getProperty(ENV_URL),
                props.getProperty("supabase.url"));
        String anonKey = firstNonBlank(
                System.getenv(ENV_ANON_KEY),
                props.getProperty(ENV_ANON_KEY),
                props.getProperty("supabase.anon.key"));
        return new SupabaseConfig(url, anonKey);
    }

    public String url() {
        return url;
    }

    public String anonKey() {
        return anonKey;
    }

    private static Properties loadProperties() {
        Properties props = new Properties();
        Path localPath = Path.of(PROPERTIES_FILE);
        if (Files.isRegularFile(localPath)) {
            try (InputStream in = Files.newInputStream(localPath)) {
                props.load(in);
                return props;
            } catch (IOException error) {
                throw new SupabaseException("Unable to read " + localPath.toAbsolutePath() + ": " + error.getMessage(), error);
            }
        }
        try (InputStream classpathIn = SupabaseConfig.class.getResourceAsStream("/" + PROPERTIES_FILE)) {
            if (classpathIn != null) {
                props.load(classpathIn);
            }
        } catch (IOException error) {
            throw new SupabaseException("Unable to read classpath " + PROPERTIES_FILE + ": " + error.getMessage(), error);
        }
        return props;
    }

    private static String firstNonBlank(String... candidates) {
        for (String value : candidates) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String missingConfigMessage(String key) {
        return "Missing Supabase configuration (" + key + "). "
                + "Set SUPABASE_URL and SUPABASE_ANON_KEY environment variables, "
                + "or copy supabase.properties.example to supabase.properties and fill in your credentials.";
    }
}
