package com.beenewed.supabase;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads Supabase project credentials from environment variables first, falling back
 * to a {@code .env} file searched in order: current working directory, the directory
 * containing the running JAR/classes (and its parent), and {@code ~/.beenewed/.env}.
 * This ensures standalone packaged JARs work regardless of launch working directory.
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
        return load(System.getenv(), defaultDotEnvCandidates());
    }

    /** Testable entry point resolving credentials from {@code env} then {@code dotEnvCandidates} in order. */
    public static SupabaseConfig load(Map<String, String> env, List<Path> dotEnvCandidates) {
        String url = env == null ? null : normalize(env.get(ENV_URL));
        String anonKey = env == null ? null : normalize(env.get(ENV_ANON_KEY));

        if ((url == null || anonKey == null) && dotEnvCandidates != null) {
            for (Path candidate : dotEnvCandidates) {
                Map<String, String> dotEnv = loadDotEnv(candidate);
                if (dotEnv.isEmpty()) {
                    continue;
                }
                if (url == null) {
                    url = normalize(dotEnv.get(ENV_URL));
                }
                if (anonKey == null) {
                    anonKey = normalize(dotEnv.get(ENV_ANON_KEY));
                }
                if (url != null && anonKey != null) {
                    break;
                }
            }
        }
        return new SupabaseConfig(url, anonKey);
    }

    static List<Path> defaultDotEnvCandidates() {
        Set<Path> paths = new LinkedHashSet<>();
        paths.add(Path.of(DOTENV_FILE).toAbsolutePath().normalize());

        Path codeDir = resolveCodeSourceDirectory();
        if (codeDir != null) {
            paths.add(codeDir.resolve(DOTENV_FILE).normalize());
            Path parent = codeDir.getParent();
            if (parent != null) {
                paths.add(parent.resolve(DOTENV_FILE).normalize());
            }
        }

        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            paths.add(Path.of(home, ".beenewed", DOTENV_FILE).toAbsolutePath().normalize());
        }
        return new ArrayList<>(paths);
    }

    private static Path resolveCodeSourceDirectory() {
        try {
            CodeSource source = SupabaseConfig.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            Path loc = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
            return Files.isDirectory(loc) ? loc : loc.getParent();
        } catch (URISyntaxException | SecurityException | IllegalArgumentException ignored) {
            return null;
        }
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
