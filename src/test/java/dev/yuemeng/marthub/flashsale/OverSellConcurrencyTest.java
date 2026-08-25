package dev.yuemeng.marthub.flashsale;

import dev.yuemeng.marthub.common.BadRequestException;
import dev.yuemeng.marthub.support.MySqlTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * The one claim in this project that a unit test cannot defend.
 *
 * <p>Overselling is a race between reading stock and writing it, so it only exists when two
 * transactions reach the same row at the same time. A mocked {@code JdbcTemplate} returns whatever
 * it was told to and would pass against the broken implementation, which makes it worse than no
 * test. This runs real threads against real InnoDB.
 *
 * <p>Verified to fail against the implementation it exists to rule out. Swapping the
 * conditional UPDATE for a read-then-write and rerunning at this size produced <b>5,063
 * orders for 5,000 units</b>, leaving the stock column at <b>-63</b>; at 1,000 units it
 * produced 1,031 and -31. A concurrency test that passes against the broken version is
 * worse than no test, so that check is part of the record rather than something assumed.
 *
 * <p><b>Transactions have to be real too.</b> {@code OrderService#create} is annotated
 * {@code @Transactional}, and outside a Spring context that annotation does nothing — the UPDATE
 * and the INSERT would autocommit separately and a constraint violation would leave the stock
 * decrement behind. So the service is built inside a small context with a real transaction
 * manager, which is also what lets the rollback be asserted rather than assumed.
 */
@EnabledIf("dev.yuemeng.marthub.support.MySqlTestSupport#mysqlAvailable")
class OverSellConcurrencyTest {

    private static final long ITEM = 101L;

    // Defaults are deliberately the scale that gets quoted, so CI runs the exact claim on every
    // push rather than a smaller stand-in for it. Overridable to reproduce a different size.
    private static final int STOCK   = env("MARTHUB_OVERSELL_STOCK", 5_000);
    private static final int BUYERS  = env("MARTHUB_OVERSELL_BUYERS", 50_000);
    private static final int THREADS = env("MARTHUB_OVERSELL_THREADS", 128);
    // Only this many attempts can be inside the database at once, so this -- not BUYERS -- is the
    // real contention level. 50,000 buyers are released together and then queue behind it.
    private static final int POOL    = env("MARTHUB_OVERSELL_POOL", 64);

    private static AnnotationConfigApplicationContext ctx;
    private static OrderService orders;
    private static JdbcTemplate jdbc;

    @Configuration
    @EnableTransactionManagement
    static class Ctx {
        @Bean DataSource dataSource() { return MySqlTestSupport.dataSource(POOL); }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PlatformTransactionManager txManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean FlashSaleMetrics metrics() { return mock(FlashSaleMetrics.class); }
        @Bean OrderService orderService(JdbcTemplate j, FlashSaleMetrics m) { return new OrderService(j, m); }
    }

    @BeforeAll
    static void setUp() {
        ctx = new AnnotationConfigApplicationContext(Ctx.class);
        orders = ctx.getBean(OrderService.class);
        jdbc = ctx.getBean(JdbcTemplate.class);
        MySqlTestSupport.applySchema(jdbc);
    }

    @AfterAll
    static void tearDown() {
        if (ctx != null) ctx.close();
    }

    @Test
    void manyMoreBuyersThanUnitsProduceExactlyOneOrderPerUnit() throws Exception {
        MySqlTestSupport.resetFixture(jdbc, ITEM, STOCK);

        AtomicInteger sold = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        AtomicInteger lockTimeouts = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            // Every buyer is a different user, so uq_user_item cannot mask an oversell by
            // rejecting a second attempt from the same person. Whatever holds the total down has
            // to be the stock check itself.
            List<Callable<Void>> attempts = IntStream.range(0, BUYERS)
                    .<Callable<Void>>mapToObj(i -> () -> {
                        start.await();
                        try {
                            orders.create(ITEM, 1_000_000L + i);
                            sold.incrementAndGet();
                        } catch (BadRequestException e) {
                            if ("SOLD_OUT".equals(e.code())) soldOut.incrementAndGet(); else throw e;
                        } catch (CannotAcquireLockException e) {
                            lockTimeouts.incrementAndGet();
                        } catch (DuplicateKeyException e) {
                            duplicates.incrementAndGet();
                        }
                        return null;
                    }).toList();

            List<Future<Void>> futures = attempts.stream().map(pool::submit).toList();
            start.countDown();                       // release them all at once
            for (Future<Void> f : futures) f.get();  // surfaces anything unexpected
        } finally {
            pool.shutdownNow();
        }

        int rowsInOrders = jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class);
        int remaining = jdbc.queryForObject(
                "SELECT stock FROM flash_sale_items WHERE id=?", Integer.class, ITEM);
        int distinctBuyers = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT user_id) FROM orders", Integer.class);

        System.out.printf("oversell: stock=%d buyers=%d threads=%d pool=%d -> sold=%d "
                        + "sold_out=%d lock_timeouts=%d duplicates=%d rows=%d remaining=%d%n",
                STOCK, BUYERS, THREADS, POOL, sold.get(), soldOut.get(),
                lockTimeouts.get(), duplicates.get(), rowsInOrders, remaining);

        // No unit may be sold twice, and none may go missing either. A test that only asserted
        // "not more than STOCK" would pass on an implementation that lost stock under contention.
        assertEquals(STOCK, rowsInOrders, "exactly one order per unit of stock");
        assertEquals(0, remaining, "every unit was sold");
        assertEquals(STOCK, distinctBuyers, "no buyer got two units");
        assertEquals(0, duplicates.get(), "distinct buyers cannot collide on uq_user_item");

        // A lock wait that ran out is a legitimate outcome, but it would mean some buyer was
        // refused for a reason unrelated to stock, and the counts above would not add up.
        assertEquals(0, lockTimeouts.get(), "no attempt was refused by a lock wait timeout");
        assertEquals(BUYERS, sold.get() + soldOut.get(), "every attempt reached a verdict");
    }

    /**
     * The scale quoted outside this repository, so CI protects the exact claim rather than a
     * neighbouring one. Small stock and a hundred times as many buyers is the shape a real sale
     * has: almost every arrival must be refused, and the few that succeed must total exactly the
     * stock.
     */
    @Test
    void aHundredUnitsAgainstTenThousandBuyersProducesExactlyAHundredOrders() throws Exception {
        int stock = 100, buyers = 10_000;
        MySqlTestSupport.resetFixture(jdbc, ITEM, stock);

        AtomicInteger sold = new AtomicInteger(), soldOut = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Callable<Void>> attempts = IntStream.range(0, buyers)
                    .<Callable<Void>>mapToObj(i -> () -> {
                        start.await();
                        try {
                            orders.create(ITEM, 5_000_000L + i);
                            sold.incrementAndGet();
                        } catch (BadRequestException e) {
                            if ("SOLD_OUT".equals(e.code())) soldOut.incrementAndGet(); else throw e;
                        }
                        return null;
                    }).toList();
            List<Future<Void>> futures = attempts.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<Void> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }

        int rows = jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class);
        int distinct = jdbc.queryForObject("SELECT COUNT(DISTINCT user_id) FROM orders", Integer.class);
        System.out.printf("oversell: stock=%d buyers=%d -> sold=%d sold_out=%d rows=%d remaining=%d%n",
                stock, buyers, sold.get(), soldOut.get(), rows, stockNow());

        assertEquals(stock, rows, "exactly one order per unit");
        assertEquals(stock, distinct, "no buyer got two units");
        assertEquals(0, stockNow(), "every unit was sold");
        assertEquals(buyers, sold.get() + soldOut.get(), "every attempt reached a verdict");
    }

    /**
     * The failed insert must take the stock decrement with it. Without a transaction around the
     * two statements, a unique-constraint violation leaves a unit deducted with no order behind
     * it -- stock quietly leaks, and the shortfall only shows up when the sale ends early.
     */
    @Test
    void aConstraintViolationReturnsTheUnitToTheShelf() {
        MySqlTestSupport.resetFixture(jdbc, ITEM, 5);
        long buyer = 42L;

        assertTrue(orders.create(ITEM, buyer) > 0);
        assertEquals(4, stockNow());

        assertThrows(DuplicateKeyException.class, () -> orders.create(ITEM, buyer));
        assertEquals(4, stockNow(), "the rolled-back attempt must not consume a unit");
        assertEquals(1, (int) jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class));
    }

    private int stockNow() {
        return jdbc.queryForObject("SELECT stock FROM flash_sale_items WHERE id=?", Integer.class, ITEM);
    }

    private static int env(String name, int fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : Integer.parseInt(v);
    }
}
