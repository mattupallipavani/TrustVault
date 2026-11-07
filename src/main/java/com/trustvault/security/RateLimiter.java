package com.trustvault.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enterprise sliding-window rate limiter.
 * Leverages Redis atomic INCR and EXPIRE for distributed rate limiting.
 * Includes an in-memory concurrent fallback if Redis is temporarily unreachable.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final StringRedisTemplate redisTemplate;
    private final ConcurrentHashMap<String, InMemoryCounter> localFallback = new ConcurrentHashMap<>();

    public RateLimiter(@Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Checks and increments request counter for the specified key within the given time window.
     *
     * @return true if the request is permitted, false if rate limit is exceeded.
     */
    public boolean tryAcquire(String key, int maxRequests, Duration window) {
        if (redisTemplate != null) {
            try {
                Long count = redisTemplate.opsForValue().increment(key);
                if (count != null && count == 1) {
                    redisTemplate.expire(key, window);
                }
                return count != null && count <= maxRequests;
            } catch (Exception e) {
                log.warn("Redis rate limiter unavailable, falling back to local limiter: {}", e.getMessage());
            }
        }

        // Local in-memory fallback
        long now = System.currentTimeMillis();
        long windowMillis = window.toMillis();
        InMemoryCounter counter = localFallback.compute(key, (k, existing) -> {
            if (existing == null || now - existing.startTime > windowMillis) {
                return new InMemoryCounter(now, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });

        return counter.count.get() <= maxRequests;
    }

    private static class InMemoryCounter {
        final long startTime;
        final AtomicInteger count;

        InMemoryCounter(long startTime, AtomicInteger count) {
            this.startTime = startTime;
            this.count = count;
        }
    }
}
