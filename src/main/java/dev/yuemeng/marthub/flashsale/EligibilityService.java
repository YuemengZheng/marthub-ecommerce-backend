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

    public String issue(long itemId, SessionUser user) {
        Integer stock = jdbc.queryForObject(
                "SELECT stock FROM flash_sale_items WHERE id=? AND active=TRUE", Integer.class, itemId);
        if (stock == null || stock <= 0) {
            throw new BadRequestException("NOT_ELIGIBLE", "sale inactive or sold out");
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

    private String tokenKey(long item, long user) { return "fs:eligibility:" + item + ":" + user; }
    private String gateKey(long item) { return "fs:gate:" + item; }
    private String boughtKey(long item, long user) { return "fs:bought:" + item + ":" + user; }
}
