package dev.yuemeng.marthub.flashsale;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Counters live in Redis, not in the JVM heap: every instance behind the load
 * balancer increments the same keys, so a benchmark that resets and reads through
 * Nginx still sees the whole cluster's totals instead of one instance's share.
 */
@Component
public class FlashSaleMetrics {
    private static final String ENTRIES = "bench:fs:orderProcessorEntries";
    private static final String REJECTIONS = "bench:fs:preOrderRejections";

    private final StringRedisTemplate redis;

    public FlashSaleMetrics(StringRedisTemplate redis) { this.redis = redis; }

    public void enteredOrderProcessor() { redis.opsForValue().increment(ENTRIES); }
    public void rejectedBeforeOrder() { redis.opsForValue().increment(REJECTIONS); }
    public long orderProcessorEntries() { return read(ENTRIES); }
    public long preOrderRejections() { return read(REJECTIONS); }
    public void reset() { redis.delete(java.util.List.of(ENTRIES, REJECTIONS)); }

    private long read(String key) {
        String v = redis.opsForValue().get(key);
        return v == null ? 0L : Long.parseLong(v);
    }
}
