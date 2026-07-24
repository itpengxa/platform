package com.caopan.platform.geo.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class TieredCacheTest {

    private static final TypeReference<String> STRING = new TypeReference<>() {};

    private TieredCache cache;

    @BeforeEach
    void setUp() {
        cache = new TieredCache(
                Caffeine.newBuilder().maximumSize(100).build(),
                null,
                new ObjectMapper(),
                false,
                Duration.ofSeconds(30));
    }

    @Test
    void get_loadsFromDbAndHitsLocalOnSecondCall() {
        AtomicInteger loads = new AtomicInteger();
        String first = cache.get("k1", STRING, Duration.ofMinutes(1), () -> {
            loads.incrementAndGet();
            return "v1";
        });
        // async L1 warm 可能尚未完成，显式 put 保证确定性
        cache.put("k1", "v1", Duration.ofMinutes(1));
        String second = cache.get("k1", STRING, Duration.ofMinutes(1), () -> {
            loads.incrementAndGet();
            return "should-not-load";
        });
        assertEquals("v1", first);
        assertEquals("v1", second);
        assertEquals(1, loads.get());
    }

    @Test
    void get_nullFromDb_writesNegativeAndReturnsNullWithoutReload() {
        AtomicInteger loads = new AtomicInteger();
        assertNull(cache.get("miss", STRING, Duration.ofMinutes(1), () -> {
            loads.incrementAndGet();
            return null;
        }));
        assertNull(cache.get("miss", STRING, Duration.ofMinutes(1), () -> {
            loads.incrementAndGet();
            return "should-not-load";
        }));
        assertEquals(1, loads.get());
    }

    @Test
    void putNegative_shortCircuitsSubsequentGets() {
        AtomicInteger loads = new AtomicInteger();
        cache.putNegative("neg");
        assertNull(cache.get("neg", STRING, Duration.ofMinutes(1), () -> {
            loads.incrementAndGet();
            return "x";
        }));
        assertEquals(0, loads.get());
    }

    @Test
    void put_nullDelegatesToNegative() {
        cache.put("n", null, Duration.ofMinutes(1));
        assertNull(cache.get("n", STRING, Duration.ofMinutes(1), () -> "x"));
    }

    @Test
    void put_andGet_sameInstanceFromLocal() {
        String value = "same";
        cache.put("s", value, Duration.ofMinutes(1));
        assertSame(value, cache.get("s", STRING, Duration.ofMinutes(1), () -> "other"));
    }
}
