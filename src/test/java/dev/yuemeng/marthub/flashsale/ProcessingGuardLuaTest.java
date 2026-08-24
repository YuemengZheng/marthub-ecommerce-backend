package dev.yuemeng.marthub.flashsale;

import dev.yuemeng.marthub.config.MartHubProperties;
import dev.yuemeng.marthub.support.RedisTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The claims worth testing here are all about what happens when two callers meet, so they are
 * tested against a real Redis with real threads. Under a mock, "exclusive" and "released only by
 * its owner" are just comments.
 */
@EnabledIf("dev.yuemeng.marthub.support.RedisTestSupport#redisAvailable")
class ProcessingGuardLuaTest {

    private static final long ITEM = 101L;
    private static final long USER = 2L;

    private StringRedisTemplate redis;
    private MartHubProperties props;
    private ProcessingGuard guard;

    @BeforeEach
    void setUp() {
        redis = RedisTestSupport.connect();
        props = new MartHubProperties();
        guard = new ProcessingGuard(redis, props);
    }

    @Test
    void onlyOneOfManySimultaneousAttemptsFromOneUserGetsThrough() throws Exception {
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Set<String> leases = ConcurrentHashMap.newKeySet();
        try {
            List<Callable<Boolean>> tasks = IntStream.range(0, threads)
                    .<Callable<Boolean>>mapToObj(i -> () -> {
                        String lease = guard.acquire(ITEM, USER);
                        if (lease == null) return false;
                        leases.add(lease);
                        return true;
                    }).toList();

            long acquired = pool.invokeAll(tasks).stream().filter(f -> {
                try { return f.get(); } catch (Exception e) { throw new IllegalStateException(e); }
            }).count();

            // The invariant is one order per user per item, so the correct concurrency limit is
            // exactly one. A 5/s token bucket with a burst of 5 answered this question with "five".
            assertEquals(1, acquired, "exactly one attempt may be in flight");
            assertEquals(1, leases.size());
        } finally {
            pool.shutdownNow();
            redis.delete("fs:processing:" + ITEM + ":" + USER);
        }
    }

    @Test
    void aHolderThatFinishesLateCannotReleaseTheNextHoldersLease() {
        String mine = guard.acquire(ITEM, USER);
        assertNotNull(mine);

        // Stand in for "my lease expired and somebody else acquired while I was still working".
        redis.opsForValue().set("fs:processing:" + ITEM + ":" + USER, "somebody-elses-lease");

        assertFalse(guard.release(ITEM, USER, mine),
                "releasing must be a no-op once the key belongs to someone else");
        assertEquals("somebody-elses-lease", redis.opsForValue().get("fs:processing:" + ITEM + ":" + USER),
                "a plain DEL here would hand away exclusivity that another request is inside");

        redis.delete("fs:processing:" + ITEM + ":" + USER);
    }

    @Test
    void releasingLetsTheSameUserTryAgainImmediately() {
        String first = guard.acquire(ITEM, USER);
        assertNotNull(first);
        assertNull(guard.acquire(ITEM, USER), "still in flight");

        assertTrue(guard.release(ITEM, USER, first));

        String second = guard.acquire(ITEM, USER);
        assertNotNull(second, "a sequential retry is legitimate; only overlap is refused");
        assertNotEquals(first, second);
        guard.release(ITEM, USER, second);
    }

    @Test
    void aLeaseNobodyReleasesExpiresOnItsOwn() throws Exception {
        props.getFlashSale().setProcessingLeaseMs(300);

        assertNotNull(guard.acquire(ITEM, USER));
        assertNull(guard.acquire(ITEM, USER));

        // Without a TTL, a JVM dying between acquire and release would lock this user out of the
        // sale permanently. The TTL is what makes that a delay instead.
        Thread.sleep(450);

        String afterExpiry = guard.acquire(ITEM, USER);
        assertNotNull(afterExpiry, "the lease must not outlive the process that took it");
        guard.release(ITEM, USER, afterExpiry);
    }

    @Test
    void twoUsersOnTheSameItemDoNotBlockEachOther() {
        String a = guard.acquire(ITEM, 10L);
        String b = guard.acquire(ITEM, 11L);
        assertNotNull(a);
        assertNotNull(b, "the lease is per user per item, not per item");
        guard.release(ITEM, 10L, a);
        guard.release(ITEM, 11L, b);
    }
}
