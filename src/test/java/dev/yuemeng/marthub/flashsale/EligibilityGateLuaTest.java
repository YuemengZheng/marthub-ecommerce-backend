package dev.yuemeng.marthub.flashsale;

import dev.yuemeng.marthub.auth.SessionUser;
import dev.yuemeng.marthub.common.BadRequestException;
import dev.yuemeng.marthub.config.MartHubProperties;
import dev.yuemeng.marthub.support.RedisTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The claim this file has to defend is that a retry does not cost a second gate slot.
 * That is enforced by an atomic Lua script, so it is tested against a real Redis with
 * real concurrency — under a mock, "atomic" is just a comment.
 */
@EnabledIf("dev.yuemeng.marthub.support.RedisTestSupport#redisAvailable")
class EligibilityGateLuaTest {

    private static final long ITEM = 101L;

    private StringRedisTemplate redis;
    private JdbcTemplate jdbc;
    private EligibilityService eligibility;

    @BeforeEach
    void setUp() {
        redis = RedisTestSupport.connect();

        // Stock comes from MySQL; only the gate arithmetic is under test here.
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(2);

        MartHubProperties props = new MartHubProperties();
        props.getFlashSale().setGateMultiplier(5);   // stock 2 x 5 => a gate of 10
        eligibility = new EligibilityService(redis, jdbc, props);
    }

    private String gate() {
        return redis.opsForValue().get("fs:gate:" + ITEM);
    }

    @Test
    void aRetryReusesTheTokenAndDoesNotSpendASecondGateSlot() {
        SessionUser user = new SessionUser(7L, "Retrying");

        String first = eligibility.issue(ITEM, user);
        assertEquals("9", gate(), "the first issue spends exactly one of ten slots");

        String second = eligibility.issue(ITEM, user);
        assertEquals(first, second, "a retry must get the same token back");
        assertEquals("9", gate(), "a retry must not spend another slot");
    }

    @Test
    void concurrentRetriesForOneUserStillSpendExactlyOneSlot() throws Exception {
        SessionUser user = new SessionUser(8L, "Impatient");
        Set<String> tokens = ConcurrentHashMap.newKeySet();

        ExecutorService pool = Executors.newFixedThreadPool(32);
        List<Callable<Boolean>> calls = IntStream.range(0, 64)
                .<Callable<Boolean>>mapToObj(i -> () -> tokens.add(eligibility.issue(ITEM, user)))
                .toList();
        pool.invokeAll(calls);
        pool.shutdown();

        assertEquals(1, tokens.size(), "64 concurrent issues handed out " + tokens.size() + " distinct tokens");
        assertEquals("9", gate(), "64 concurrent issues spent more than one gate slot");
    }

    @Test
    void theGateClosesOnceItIsDrained() {
        for (int i = 0; i < 10; i++) {
            eligibility.issue(ITEM, new SessionUser(100 + i, "User" + i));
        }
        assertEquals("0", gate(), "ten distinct users should drain a gate of ten");

        BadRequestException e = assertThrows(BadRequestException.class,
                () -> eligibility.issue(ITEM, new SessionUser(999L, "TooLate")));
        assertEquals("GATE_CLOSED", e.code());
    }

    @Test
    void anIssuedTokenValidatesOnlyForItsOwnUser() {
        String token = eligibility.issue(ITEM, new SessionUser(11L, "Owner"));

        assertTrue(eligibility.valid(ITEM, 11L, token));
        assertFalse(eligibility.valid(ITEM, 12L, token), "another user must not pass with this token");
        assertFalse(eligibility.valid(ITEM, 11L, "not-the-token"));
    }

    @Test
    void aBoughtMarkerBlocksAnotherToken() {
        SessionUser user = new SessionUser(13L, "Buyer");
        eligibility.issue(ITEM, user);
        eligibility.markBought(ITEM, user.id(), 7001L);

        BadRequestException e = assertThrows(BadRequestException.class, () -> eligibility.issue(ITEM, user));
        assertEquals("ALREADY_BOUGHT", e.code());
    }

    @Test
    void buyingRetiresTheTokenSoARetryIsRefusedAtTheFirstGate() {
        SessionUser buyer = new SessionUser(77L, "Buyer");
        String token = eligibility.issue(500L, buyer);
        assertTrue(eligibility.valid(500L, buyer.id(), token), "usable before the purchase");

        eligibility.markBought(500L, buyer.id(), 7002L);
        eligibility.revokeToken(500L, buyer.id());

        // Without this the buyer kept clearing admission for the rest of the token's life: a slot
        // out of the shared bucket and a lock on the contended stock row, every retry, only to
        // fail on the unique constraint at the end.
        assertFalse(eligibility.valid(500L, buyer.id(), token),
                "a purchase that succeeded must not leave a usable token behind");
    }

    @Test
    void aBuyerCannotSimplyTakeAFreshToken() {
        SessionUser buyer = new SessionUser(78L, "Buyer");
        eligibility.issue(501L, buyer);
        eligibility.markBought(501L, buyer.id(), 7003L);
        eligibility.revokeToken(501L, buyer.id());

        // The bought marker outlives the token, which is what closes the other entrance.
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> eligibility.issue(501L, buyer));
        assertEquals("ALREADY_BOUGHT", e.code());
    }

    @Test
    void afterASellOutTheUnthrottledEntranceStopsReachingTheDatabase() {
        eligibility.markSoldOut(ITEM);

        BadRequestException e = assertThrows(BadRequestException.class,
                () -> eligibility.issue(ITEM, new SessionUser(2L, "LoadTest")));

        assertEquals("SOLD_OUT", e.code());
        // Token issuance has no bucket in front of it, so this is the one endpoint where a
        // post-sell-out retry storm arrives at full strength. One Redis key lookup answers it.
        verifyNoInteractions(jdbc);
        assertTrue(eligibility.soldOut(ITEM));

        eligibility.clearSoldOut(ITEM);
        assertFalse(eligibility.soldOut(ITEM));
    }
}
