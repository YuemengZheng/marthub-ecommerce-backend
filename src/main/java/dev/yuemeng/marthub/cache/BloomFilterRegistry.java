package dev.yuemeng.marthub.cache;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

@Component
public class BloomFilterRegistry {
    private final JdbcTemplate jdbc;
    private volatile LongBloomFilter shopIds = new LongBloomFilter(10000, 0.01);
    public BloomFilterRegistry(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @EventListener(ApplicationReadyEvent.class) public void reload() {
        LongBloomFilter next = new LongBloomFilter(10000, 0.01);
        jdbc.query("SELECT id FROM shops", (RowCallbackHandler) rs -> next.put(rs.getLong(1)));
        shopIds = next;
    }
    public boolean mightContainShop(long id) { return shopIds.mightContain(id); }
    public void addShop(long id) { shopIds.put(id); }
}
