package dev.yuemeng.marthub.flashsale;

import dev.yuemeng.marthub.common.BadRequestException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    private final JdbcTemplate jdbc; private final FlashSaleMetrics metrics;
    public OrderService(JdbcTemplate jdbc, FlashSaleMetrics metrics){this.jdbc=jdbc;this.metrics=metrics;}
    @Transactional public long create(long itemId,long userId){
        metrics.enteredOrderProcessor();
        int updated=jdbc.update("UPDATE flash_sale_items SET stock=stock-1 WHERE id=? AND active=TRUE AND stock>0",itemId);
        if(updated==0) throw new BadRequestException("SOLD_OUT","sold out");
        jdbc.update("INSERT INTO orders(user_id,item_id) VALUES (?,?)",userId,itemId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
    }
    /**
     * The durable answer to "does this user already have an order for this item".
     *
     * <p>Deliberately <b>not</b> {@code @Transactional} and deliberately called from outside
     * {@link #create}: the insert that failed decremented stock in the same transaction, so that
     * transaction has to roll back before this runs. Catching the constraint violation inside
     * {@code create} and committing anyway would take a unit of stock off the shelf without
     * producing an order to match it.
     */
    public Long findExistingOrder(long itemId, long userId) {
        return jdbc.query("SELECT id FROM orders WHERE user_id=? AND item_id=?",
                rs -> rs.next() ? rs.getLong(1) : null, userId, itemId);
    }

    /** Benchmark-only legacy shape: request reaches order processing before eligibility is checked. */
    public void baselineInvalidAttempt(){
        metrics.enteredOrderProcessor();
        // Legacy-shaped validation inside the order-processing boundary: three DB reads
        // that the optimized eligibility-token path avoids for obviously invalid traffic.
        jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=2", Long.class);
        jdbc.queryForObject("SELECT COUNT(*) FROM flash_sale_items WHERE id=101 AND active=TRUE", Long.class);
        jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE user_id=2 AND item_id=101", Long.class);
    }
}
