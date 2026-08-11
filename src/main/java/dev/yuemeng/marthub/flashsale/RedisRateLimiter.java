package dev.yuemeng.marthub.flashsale;

import dev.yuemeng.marthub.config.MartHubProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RedisRateLimiter {
    private static final String LUA = """
        local key=KEYS[1]
        local now=tonumber(ARGV[1])
        local rate=tonumber(ARGV[2])
        local capacity=tonumber(ARGV[3])
        local data=redis.call('HMGET',key,'tokens','ts')
        local tokens=tonumber(data[1])
        local ts=tonumber(data[2])
        if tokens==nil then tokens=capacity end
        if ts==nil then ts=now end
        tokens=math.min(capacity, tokens + ((now-ts)/1000.0)*rate)
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

    public boolean allow(long itemId) {
        Long result = redis.execute(
                script,
                List.of(key(itemId)),
                Long.toString(System.currentTimeMillis()),
                Double.toString(props.getFlashSale().getRatePerSecond()),
                Integer.toString(props.getFlashSale().getBurstCapacity()));
        return result != null && result == 1L;
    }

    /** Benchmark support: clears only the limiter state for one item. */
    public void reset(long itemId) {
        redis.delete(key(itemId));
    }

    private String key(long itemId) { return "fs:rate:" + itemId; }
}
