package com.caopan.platform.geo.report;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 上报频控（按 client 每小时，GEO-002）。
 */
@Component
public class ReportRateLimiter {

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public boolean tryAcquire(String clientCode, int limitPerHour) {
        if (limitPerHour <= 0) {
            return true;
        }
        String key = clientCode == null ? "anonymous" : clientCode;
        long hour = LocalDateTime.now().getHour() + LocalDateTime.now().getDayOfYear() * 24L;
        Window w = windows.compute(key, (k, old) -> {
            if (old == null || old.hour != hour) {
                return new Window(hour, new AtomicInteger(0));
            }
            return old;
        });
        return w.count.incrementAndGet() <= limitPerHour;
    }

    private static final class Window {
        final long hour;
        final AtomicInteger count;

        Window(long hour, AtomicInteger count) {
            this.hour = hour;
            this.count = count;
        }
    }
}
