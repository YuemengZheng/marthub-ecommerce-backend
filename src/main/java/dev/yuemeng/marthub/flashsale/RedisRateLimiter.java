package dev.yuemeng.marthub.flashsale;

import dev.yuemeng.marthub.config.MartHubProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token bucket in Lua, in two layers.
 *
 * <p>Refill, check and decrement have to happen without anything interleaving, so the whole
 * bucket lives in one script: Redis runs it single-threaded, which is what makes a burst of
 * concurrent callers land on exactly the configured capacity instead of over-admitting.
 *
 * <p>The clock comes from {@code TIME} inside Redis rather than from the caller. Passing
 * {@code System.currentTimeMillis()} in meant every instance contributed its own clock to a
 * bucket they share: if one instance ran even slightly behind the one that last wrote {@code ts},
 * the elapsed term went negative and silently removed tokens nobody had spent.
 */
@Component
public class RedisRateLimiter {
    private static final String LUA = """
        local key=KEYS[1]
        local rate=tonumber(ARGV[1])
        local capacity=tonumber(ARGV[2])
        local t=redis.call('TIME')
        local now=tonumber(t[1])*1000 + math.floor(tonumber(t[2])/1000)
        local data=redis.call('HMGET',key,'tokens','ts')
        local tokens=tonumber(data[1])
        local ts=tonumber(data[2])
        if tokens==nil then tokens=capacity end
        if ts==nil then ts=now end
        local elapsed=now-ts
        if elapsed < 0 then elapsed=0 end
        tokens=math.min(capacity, tokens + (elapsed/1000.0)*rate)
        local allowed=0
        if tokens >= 1 then tokens=tokens-1 allowed=1 end
        redis.call('HMSET',key,'tokens',tokens,'ts',now)
        redis.call('PEXPIRE',key,math.ceil((capacity/rate)*2000))
        return allowed
        """;
    private final StringRedisTemplate redis;
    private final MartHubProperties props;
    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA, Long.class);

    public RedisRateLimiter(StringRedisTemplate redis, MartHubProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** Shared bucket for one item: the layer that protects the database from the whole crowd. */
    public boolean allow(long itemId) {
        return consume(itemKey(itemId),
                props.getFlashSale().getRatePerSecond(),
                props.getFlashSale().getBurstCapacity());
    }

    /**
     * Per-user bucket, meant to be checked first. The item bucket alone cannot tell one user
     * hammering the endpoint apart from a crowd arriving at once, so on its own it lets a single
     * caller spend everyone else's tokens.
     */
    public boolean allowUser(long itemId, long userId) {
        return consume(userKey(itemId, userId),
                props.getFlashSale().getUserRatePerSecond(),
                props.getFlashSale().getUserBurstCapacity());
    }

    private boolean consume(String key, double rate, int capacity) {
        Long result = redis.execute(script, List.of(key), Double.toString(rate), Integer.toString(capacity));
        return result != null && result == 1L;
    }

    /** Benchmark support: clears only the limiter state for one item. */
    public void reset(long itemId) {
        redis.delete(itemKey(itemId));
    }

    /** Benchmark support: clears one user's bucket for one item. */
    public void resetUser(long itemId, long userId) {
        redis.delete(userKey(itemId, userId));
    }

    private String itemKey(long itemId) { return "fs:rate:" + itemId; }
    private String userKey(long itemId, long userId) { return "fs:rate:u:" + itemId + ":" + userId; }
}
