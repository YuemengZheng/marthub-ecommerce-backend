package dev.yuemeng.marthub.flashsale;

import dev.yuemeng.marthub.config.MartHubProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * At most one in-flight order attempt per (user, item).
 *
 * <p>This replaces a per-user token bucket, and the reason is that the bucket was the wrong shape
 * for the requirement. The invariant is {@code uq_user_item}: one order per user per item. So the
 * correct concurrency limit is exactly one, and a mutex states that exactly, where a rate limit
 * only approximated it -- 5/s with a burst of 5 let a user's five simultaneous requests all
 * through, all the way to a lock on the hottest row in the system, four of them to be rolled back
 * by the constraint. It bounded how fast, when what mattered was how many at once.
 *
 * <p><b>This is an optimization, not a safety mechanism.</b> The lease expires on a timer and
 * carries no fencing token, so two attempts can genuinely overlap: a holder that stalls past the
 * TTL will still be running when the next one acquires. What prevents a double order is
 * {@code uq_user_item}, and nothing here. The guard's job is only to shed duplicate work cheaply,
 * before it reaches the shared rate limiter or the database.
 */
@Component
public class ProcessingGuard {

    /**
     * Compare-and-delete, because a plain {@code DEL} releases whoever holds the key rather than
     * the caller's own lease. Once A's lease has expired and B has acquired, A finishing its work
     * and deleting the key would hand B's exclusivity away while B is still inside it.
     */
    private static final String RELEASE_LUA = """
        if redis.call('GET',KEYS[1]) == ARGV[1] then
          return redis.call('DEL',KEYS[1])
        end
        return 0
        """;

    private final StringRedisTemplate redis;
    private final MartHubProperties props;
    private final DefaultRedisScript<Long> releaseScript = new DefaultRedisScript<>(RELEASE_LUA, Long.class);

    public ProcessingGuard(StringRedisTemplate redis, MartHubProperties props) {
        this.redis = redis;
        this.props = props;
    }

    /** @return the lease to release with, or {@code null} if this user already has one in flight. */
    public String acquire(long itemId, long userId) {
        String lease = UUID.randomUUID().toString().replace("-", "");
        Boolean acquired = redis.opsForValue().setIfAbsent(
                key(itemId, userId), lease,
                Duration.ofMillis(props.getFlashSale().getProcessingLeaseMs()));
        return Boolean.TRUE.equals(acquired) ? lease : null;
    }

    /** Must run in a finally block: a lease that is never released locks the user out until it expires. */
    public boolean release(long itemId, long userId, String lease) {
        Long deleted = redis.execute(releaseScript, List.of(key(itemId, userId)), lease);
        return deleted != null && deleted == 1L;
    }

    private String key(long itemId, long userId) { return "fs:processing:" + itemId + ":" + userId; }
}
