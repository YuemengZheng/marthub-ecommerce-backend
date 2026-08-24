package dev.yuemeng.marthub.flashsale;

import dev.yuemeng.marthub.config.MartHubProperties;
import dev.yuemeng.marthub.support.RedisTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The token bucket is a Lua script, so a mock proves nothing about it: the whole
 * point is that read-modify-write happens inside Redis, under contention, across
 * callers that never coordinate. This runs it against a real Redis.
 */
@EnabledIf("dev.yuemeng.marthub.support.RedisTestSupport#redisAvailable")
class RedisRateLimiterLuaTest {

    private StringRedisTemplate redis;
    private MartHubProperties props;

    @BeforeEach
    void setUp() {
        redis = RedisTestSupport.connect();
        props = new MartHubProperties();
    }

    private RedisRateLimiter limiter(double ratePerSecond, int burstCapacity, long itemId) {
        props.getFlashSale().setRatePerSecond(ratePerSecond);
        props.getFlashSale().setBurstCapacity(burstCapacity);
        RedisRateLimiter limiter = new RedisRateLimiter(redis, props);
        limiter.reset(itemId);
        return limiter;
    }

    @Test
    void aConcurrentBurstIsCappedNearTheConfiguredCapacity() throws Exception {
        long itemId = 1L;
        RedisRateLimiter limiter = limiter(20, 20, itemId);

        ExecutorService pool = Executors.newFixedThreadPool(50);
        List<Callable<Boolean>> calls = IntStream.range(0, 200)
                .<Callable<Boolean>>mapToObj(i -> () -> limiter.allow(itemId))
                .toList();
        long allowed = pool.invokeAll(calls).stream().filter(RedisRateLimiterLuaTest::get).count();
        pool.shutdown();

        // 20 from the bucket, plus whatever trickles in at 20/s while the burst runs.
        assertTrue(allowed >= 20, "the full burst capacity should be admitted, got " + allowed);
        assertTrue(allowed <= 30, "far more than capacity was admitted, got " + allowed);
    }

    @Test
    void anExhaustedBucketRefusesImmediatelyAndRefillsOverTime() throws Exception {
        long itemId = 2L;
        RedisRateLimiter limiter = limiter(20, 5, itemId);

        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.allow(itemId), "call " + i + " should come out of the initial bucket");
        }
        assertFalse(limiter.allow(itemId), "the bucket is empty, this must be refused");

        // 20 tokens/second means 300ms is worth about 6 — more than enough for one.
        Thread.sleep(300);
        assertTrue(limiter.allow(itemId), "the bucket should have refilled while we waited");
    }

    @Test
    void separateItemsDoNotShareABucket() {
        RedisRateLimiter limiter = limiter(20, 1, 3L);
        limiter.reset(4L);

        assertTrue(limiter.allow(3L));
        assertFalse(limiter.allow(3L), "item 3's single token is spent");
        assertTrue(limiter.allow(4L), "item 4 has its own bucket");
    }

    private static boolean get(Future<Boolean> f) {
        try {
            return f.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
