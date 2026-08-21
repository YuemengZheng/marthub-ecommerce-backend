package dev.yuemeng.marthub.cache;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

/**
 * The set of shop ids that might exist, so a read for one that never did costs
 * neither a cache lookup nor a query.
 *
 * <p>Two things this has to get right, because a Bloom filter in front of a read path
 * fails closed by nature -- a wrong "absent" is a 400 for a row that exists:
 *
 * <ul>
 * <li><b>Before the first load it answers yes to everything.</b> The servlet connector
 * starts during context refresh, but this loads on {@code ApplicationReadyEvent}, which
 * fires after. Requests can arrive in between, and an empty filter would reject every
 * one of them. Fail open until there is something to say no with.
 * <li><b>It is sized from the table, not from a constant.</b> A filter built for 10,000
 * ids holding 10,000 of them sits at its design false-positive rate, and every false
 * positive is a real query, since absent ids are not negatively cached. Room to grow is
 * cheap: the bitset is a few KB either way.
 * </ul>
 */
@Component
public class BloomFilterRegistry {
    private static final double FALSE_POSITIVE_RATE = 0.01;
    private static final int MIN_CAPACITY = 1000;
    private static final int HEADROOM = 2;

    private final JdbcTemplate jdbc;
    /** Null means "not loaded yet", which is why reads fail open. */
    private volatile LongBloomFilter shopIds;

    public BloomFilterRegistry(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @EventListener(ApplicationReadyEvent.class) public void reload() {
        Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM shops", Long.class);
        int capacity = (int) Math.max(MIN_CAPACITY, (rows == null ? 0L : rows) * HEADROOM);
        LongBloomFilter next = new LongBloomFilter(capacity, FALSE_POSITIVE_RATE);
        jdbc.query("SELECT id FROM shops", (RowCallbackHandler) rs -> next.put(rs.getLong(1)));
        shopIds = next;
    }

    public boolean mightContainShop(long id) {
        LongBloomFilter current = shopIds;
        return current == null || current.mightContain(id);
    }

    public void addShop(long id) {
        LongBloomFilter current = shopIds;
        if (current != null) current.put(id);
    }
}
