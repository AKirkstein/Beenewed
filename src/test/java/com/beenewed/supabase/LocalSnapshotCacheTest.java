package com.beenewed.supabase;

import com.beenewed.backend.model.AppPreferences;
import com.beenewed.backend.model.BillingCycle;
import com.beenewed.backend.model.Contribution;
import com.beenewed.backend.model.Member;
import com.beenewed.backend.model.Payment;
import com.beenewed.backend.model.PriceHistory;
import com.beenewed.backend.model.Subscription;
import com.beenewed.backend.model.SubscriptionStatus;
import com.beenewed.backend.model.Theme;
import com.beenewed.backend.model.UsageLog;
import com.beenewed.supabase.LocalSnapshotCache.CachedSnapshot;
import com.beenewed.supabase.SupabaseRestClient.UserDataSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LocalSnapshotCacheTest {

    private static UserDataSnapshot sampleSnapshot(String subName) {
        LocalDateTime ts = LocalDateTime.of(2026, 11, 23, 12, 30, 0);
        Subscription sub = new Subscription(
                1L, subName, "Entertainment", "television",
                new BigDecimal("549.00"), BillingCycle.MONTHLY,
                LocalDate.of(2026, 12, 1), SubscriptionStatus.ACTIVE, ts, ts);
        Payment pay = new Payment(
                10L, 1L, LocalDate.of(2026, 11, 23), new BigDecimal("549.00"),
                "November 2026", "GCash", "Paid", ts);
        Member member = new Member(20L, 1L, "Ana", "Sister", ts, ts);
        Contribution contrib = new Contribution(
                30L, 20L, 10L, new BigDecimal("274.50"), LocalDate.of(2026, 11, 23), ts);
        UsageLog usage = new UsageLog(
                40L, 1L, LocalDate.of(2026, 11, 22), 2.5, "Hours", "Watched movie", ts);
        PriceHistory ph = new PriceHistory(
                50L, 1L, new BigDecimal("499.00"), new BigDecimal("549.00"),
                LocalDate.of(2026, 10, 1), "Plan increase", ts);
        AppPreferences prefs = new AppPreferences(1L, "PHP", Theme.DARK, 14, true);

        return new UserDataSnapshot(
                List.of(sub), List.of(pay), List.of(member),
                List.of(contrib), List.of(usage), List.of(ph), prefs);
    }

    @Test
    void roundTripsAllSevenTablesAndUpsertsCleanly(@TempDir Path tempDir) {
        Path dbFile = tempDir.resolve("offline-cache.db");
        LocalSnapshotCache cache = new LocalSnapshotCache(dbFile);
        assertFalse(cache.hasAnySnapshot());

        cache.save("uid-1", "Kirk@Example.com", sampleSnapshot("Netflix"));
        assertTrue(cache.hasAnySnapshot());

        Optional<CachedSnapshot> byId = cache.loadByUserId("uid-1");
        assertTrue(byId.isPresent());
        assertEquals("Kirk@Example.com", byId.get().email());
        assertNotNull(byId.get().savedAt());

        UserDataSnapshot snap = byId.get().snapshot();
        assertEquals(1, snap.subscriptions().size());
        assertEquals("Netflix", snap.subscriptions().get(0).name());
        assertEquals(LocalDate.of(2026, 12, 1), snap.subscriptions().get(0).nextRenewalDate());
        assertEquals(1, snap.payments().size());
        assertEquals(new BigDecimal("549.00"), snap.payments().get(0).amountPaid());
        assertEquals(1, snap.members().size());
        assertEquals(1, snap.contributions().size());
        assertEquals(1, snap.usageLogs().size());
        assertEquals(1, snap.priceHistory().size());
        assertEquals(Theme.DARK, snap.preferences().theme());

        // Case-insensitive email lookup works
        assertTrue(cache.loadByEmail("kirk@example.com").isPresent());

        // Upsert updates existing user row cleanly
        cache.save("uid-1", "Kirk@Example.com", sampleSnapshot("Netflix 4K"));
        assertEquals("Netflix 4K", cache.loadLatest().orElseThrow().snapshot().subscriptions().get(0).name());

        // Clear removes user data cleanly
        cache.clearForUser("uid-1");
        assertFalse(cache.hasAnySnapshot());
    }

    @Test
    void supabaseConfigResolvesDotEnvFromFallbackDirectory(@TempDir Path tempDir) throws Exception {
        Path missingCwd = tempDir.resolve("cwd").resolve(".env");
        Path jarDirEnv = tempDir.resolve("jar-dir").resolve(".env");
        java.nio.file.Files.createDirectories(jarDirEnv.getParent());
        java.nio.file.Files.writeString(jarDirEnv,
                "SUPABASE_URL=https://example.supabase.co/\nSUPABASE_ANON_KEY=anon-key-123\n");

        SupabaseConfig cfg = SupabaseConfig.load(Map.of(), List.of(missingCwd, jarDirEnv));
        assertEquals("https://example.supabase.co", cfg.url());
        assertEquals("anon-key-123", cfg.anonKey());

        // Environment variables always override .env files
        SupabaseConfig fromEnv = SupabaseConfig.load(
                Map.of(SupabaseConfig.ENV_URL, "https://env.supabase.co", SupabaseConfig.ENV_ANON_KEY, "env-key"),
                List.of(jarDirEnv));
        assertEquals("https://env.supabase.co", fromEnv.url());
        assertEquals("env-key", fromEnv.anonKey());
    }
}
