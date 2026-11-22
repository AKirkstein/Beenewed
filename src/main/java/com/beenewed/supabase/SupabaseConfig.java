package com.beenewed.supabase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads Supabase project credentials from environment variables first, falling back
 * to a local {@code .env} file in the working directory when an environment variable
 * is missing or blank.
 */
public final class SupabaseConfig {
    public static final String ENV_URL = "SUPABASE_URL";
    public static final String ENV_ANON_KEY = "SUPABASE_ANON_KEY";
    public static final String DOTENV_FILE = ".env";

    private final String url;
    private final String anonKey;

    public SupabaseConfig(String url, String anonKey) {
        if (url == null || url.isBlank()) {
            throw new SupabaseException(missingConfigMessage(ENV_URL));
        }
        if (anonKey == null || anonKey.isBlank()) {
            throw new SupabaseException(missingConfigMessage(ENV_ANON_KEY));
        }
        String normalizedUrl = url.trim();
        while (normalizedUrl.endsWith("/")) {
            normalizedUrl = normalizedUrl.substring(0, normalizedUrl.length() - 1);
        }
        String normalizedKey = anonKey.trim();
        if (normalizedUrl.contains("your-supabase-project-url")
                || normalizedUrl.contains("your-project-ref")
                || normalizedKey.equals("your-supabase-anon-key")) {
            throw new SupabaseException(
                    "Supabase credentials still contain placeholder values. "
                            + "Set SUPABASE_URL and SUPABASE_ANON_KEY as environment variables or in .env.");
        }
        this.url = normalizedUrl;
        this.anonKey = normalizedKey;
    }

    public static SupabaseConfig load() {
        String url = normalize(System.getenv(ENV_URL));
        String anonKey = normalize(System.getenv(ENV_ANON_KEY));

        if (url == null || anonKey == null) {
            Map<String, String> dotEnv = loadDotEnv(Path.of(DOTENV_FILE));
            if (url == null) {
                url = normalize(dotEnv.get(ENV_URL));
            }
            if (anonKey == null) {
                anonKey = normalize(dotEnv.get(ENV_ANON_KEY));
            }
        }
        return new SupabaseConfig(url, anonKey);
    }

    public String url() {
        return url;
    }

    public String anonKey() {
        return anonKey;
    }

    private static Map<String, String> loadDotEnv(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return Collections.emptyMap();
        }
        try {
            return parseDotEnv(Files.readAllLines(path, StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new SupabaseException(
                    "Unable to read " + path.toAbsolutePath() + ": " + error.getMessage(),
                    error);
        }
    }

    static Map<String, String> parseDotEnv(List<String> lines) {
        Map<String, String> values = new HashMap<>();
        if (lines == null) {
            return values;
        }
        for (String rawLine : lines) {
            if (rawLine == null) {
                continue;
            }
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ") && line.length() > 7) {
                line = line.substring(7).trim();
            }
            int equalsIndex = line.indexOf('=');
            if (equalsIndex <= 0) {
                continue;
            }
            String key = line.substring(0, equalsIndex).trim();
            String value = stripMatchingQuotes(line.substring(equalsIndex + 1).trim());
            if (!key.isEmpty()) {
                values.put(key, value);
            }
        }
        return values;
    }

    private static String stripMatchingQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String missingConfigMessage(String key) {
        return "Missing Supabase configuration (" + key + "). "
                + "Set SUPABASE_URL and SUPABASE_ANON_KEY as environment variables, "
                + "or copy .env.example to .env in the project root and fill in your credentials.";
    }
}
