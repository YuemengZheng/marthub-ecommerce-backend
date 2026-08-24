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
     * Records which order the purchase produced, so a repeat of that request can be answered with
     * it instead of an error.
     *
     * <p>It stores the order id rather than a flag because the marker's job changed. As a flag all
     * it could do was refuse; holding the id lets the first gate return the caller's own order, and
     * a repeated request is then a replay rather than a failure -- which is what a client whose
     * response was lost on the network actually needs.
     *
     * <p><b>This is a cache, not the record.</b> The durable answer is a row in {@code orders}
     * behind {@code uq_user_item}; nothing here is safe from eviction, and no {@code maxmemory} is
     * configured. Losing this key must therefore degrade to the slow path rather than to a wrong
     * answer, which is why the order path also resolves the id from MySQL when the constraint
     * refuses an insert.
     *
     * <p>Written before the token is revoked, on purpose. Crashing between the two leaves a live
     * token next to a recorded purchase, and the next request is answered by the first gate for
     * free. Reversed, the same crash leaves neither, and recovery costs a row lock.
     */
    public void markBought(long itemId, long userId, long orderId) {
        redis.opsForValue().set(boughtKey(itemId, userId), Long.toString(orderId),
                java.time.Duration.ofHours(6));
    }

    /**
     * @return the order this user already has for this item, or {@code null} if Redis does not know
     *         of one -- which is not the same as there not being one.
     */
    public Long boughtOrderId(long itemId, long userId) {
        String value = redis.opsForValue().get(boughtKey(itemId, userId));
        if (value == null) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            // A marker written by an older version held a flag, not an id. Treating that as "no
            // fast answer" sends the request down the path that resolves the id from MySQL, so the
            // key heals itself instead of failing the request.
            return null;
        }
    }

    /**
     * Retires the token that authorised a purchase. Separate from recording the purchase because
     * the order of the two matters and burying it inside one method hid that.
     *
     * <p>A token exists exactly while another attempt is still possible. Leaving it in place let a
     * buyer who had already succeeded keep clearing admission for the rest of the token's life --
     * each retry taking a lock on the contended stock row, only to be refused by the unique
     * constraint.
     */
    public void revokeToken(long itemId, long userId) {
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
