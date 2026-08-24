package dev.yuemeng.marthub.flashsale;

import dev.yuemeng.marthub.auth.SessionUser;
import dev.yuemeng.marthub.common.BadRequestException;
import dev.yuemeng.marthub.config.MartHubProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class EligibilityService {
    /**
     * Atomically reuses an existing token for the same user/item. Only the first
     * successful issuance consumes one gate slot, which prevents refresh/retry
     * traffic from draining the global eligibility gate.
     */
    private static final String ISSUE_LUA = """
        local tokenKey=KEYS[1]
        local gateKey=KEYS[2]
        local proposed=ARGV[1]
        local initialGate=tonumber(ARGV[2])
        local ttlSeconds=tonumber(ARGV[3])

        local existing=redis.call('GET',tokenKey)
        if existing then return existing end

        redis.call('SETNX',gateKey,initialGate)
        redis.call('EXPIRE',gateKey,3600)
        local remaining=tonumber(redis.call('GET',gateKey) or '0')
        if remaining <= 0 then return '__GATE_CLOSED__' end

        redis.call('DECR',gateKey)
        redis.call('SET',tokenKey,proposed,'EX',ttlSeconds)
        return proposed
        """;

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final MartHubProperties props;
    private final DefaultRedisScript<String> issueScript = new DefaultRedisScript<>(ISSUE_LUA, String.class);

    public EligibilityService(StringRedisTemplate redis, JdbcTemplate jdbc, MartHubProperties props) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.props = props;
    }

    /**
     * Issuing is the one unthrottled entrance: it reads MySQL on every call and no bucket stands in
     * front of it. So the sold-out flag is checked first here too -- after a sell-out this endpoint
     * is exactly where a retry storm lands, and answering it from Redis keeps that storm off the
     * database.
     *
     * <p>The two refusals are kept apart because they are different facts. {@code active=TRUE} is
     * in the query, so a null result means the sale is not running, while a non-positive stock
     * means it ran and finished; only the second one is worth remembering.
     */
    public String issue(long itemId, SessionUser user) {
        if (soldOut(itemId)) {
            throw new BadRequestException("SOLD_OUT", "sold out");
        }
        Integer stock = jdbc.queryForObject(
                "SELECT stock FROM flash_sale_items WHERE id=? AND active=TRUE", Integer.class, itemId);
        if (stock == null) {
            throw new BadRequestException("NOT_ELIGIBLE", "sale inactive");
        }
        if (stock <= 0) {
            markSoldOut(itemId);
            throw new BadRequestException("SOLD_OUT", "sold out");
        }
        if (Boolean.TRUE.equals(redis.hasKey(boughtKey(itemId, user.id())))) {
            throw new BadRequestException("ALREADY_BOUGHT", "already purchased");
        }

        String proposed = UUID.randomUUID().toString().replace("-", "");
        String token = redis.execute(
                issueScript,
                List.of(tokenKey(itemId, user.id()), gateKey(itemId)),
                proposed,
                Integer.toString(stock * props.getFlashSale().getGateMultiplier()),
                Long.toString(props.getFlashSale().getTokenTtlSeconds()));

        if (token == null || "__GATE_CLOSED__".equals(token)) {
            throw new BadRequestException("GATE_CLOSED", "eligibility gate exhausted");
        }
        return token;
    }

    public boolean valid(long itemId, long userId, String token) {
        String expected = redis.opsForValue().get(tokenKey(itemId, userId));
        return expected != null && expected.equals(token);
    }

    /**
     * Records the purchase and retires the token that authorised it.
     *
     * <p>Dropping the token is what makes the state machine mean something: a token exists exactly
     * while another attempt is still possible. Leaving it in place let a buyer who had already
     * succeeded keep passing admission for the rest of the token's life -- each retry spending a
     * slot from the bucket everyone shares, taking a lock on the contended stock row, and only
     * then failing on the unique constraint. Correct, because the constraint held, but paid for at
     * full price and reported as a 500.
     *
     * <p>The bought marker outlives the token on purpose: it is what stops a fresh token being
     * issued, so the two together say "already bought" at both entrances.
     */
    public void markBought(long itemId, long userId) {
        redis.opsForValue().set(boughtKey(itemId, userId), "1", java.time.Duration.ofHours(6));
        redis.delete(tokenKey(itemId, userId));
    }

    /**
     * Remembers that a sale has run out, so the next caller can be turned away for the right
     * reason and at the right price.
     *
     * <p>Before this existed the system had nowhere to record that an item was finished, so every
     * retry after the last unit went the whole way: token check, both buckets, then a lock on the
     * contended stock row to learn what the previous request already knew. The per-user limiter was
     * capping that storm, which is the wrong tool -- a rate limit says "too fast", not "this is
     * over".
     *
     * <p>It is a cached fact and can therefore go stale: restocking or re-activating an item would
     * need to clear it, and this project has no restock path, so a TTL bounds how wrong it can get.
     * Set on observing a sell-out rather than by predicting one, so the first caller after the last
     * unit still pays full price and everyone after them does not.
     */
    public void markSoldOut(long itemId) {
        redis.opsForValue().set(soldOutKey(itemId), "1",
                java.time.Duration.ofHours(props.getFlashSale().getSoldOutTtlHours()));
    }

    public boolean soldOut(long itemId) {
        return Boolean.TRUE.equals(redis.hasKey(soldOutKey(itemId)));
    }

    /** Benchmark support: a sell-out from one run must not decide the next one. */
    public void clearSoldOut(long itemId) {
        redis.delete(soldOutKey(itemId));
    }

    private String tokenKey(long item, long user) { return "fs:eligibility:" + item + ":" + user; }
    private String soldOutKey(long item) { return "fs:soldout:" + item; }
    private String gateKey(long item) { return "fs:gate:" + item; }
    private String boughtKey(long item, long user) { return "fs:bought:" + item + ":" + user; }
}
