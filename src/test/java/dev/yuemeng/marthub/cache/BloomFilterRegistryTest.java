package dev.yuemeng.marthub.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A Bloom filter in front of a read path fails closed: a wrong "absent" answer is a 400
 * for a row that exists. These two tests are about the ways that can happen for reasons
 * other than the filter's own false-positive rate.
 */
class BloomFilterRegistryTest {

    private JdbcTemplate jdbc;
    private BloomFilterRegistry registry;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        registry = new BloomFilterRegistry(jdbc);
    }

    /** Feed the registry a table of ids, as JdbcTemplate#query would. */
    private void tableContains(long... ids) {
        when(jdbc.queryForObject(contains("COUNT"), eq(Long.class))).thenReturn((long) ids.length);
        doAnswer(inv -> {
            RowCallbackHandler handler = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            for (long id : ids) {
                when(rs.getLong(1)).thenReturn(id);
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(contains("SELECT id"), any(RowCallbackHandler.class));
    }

    @Test
    void beforeTheFirstLoadEveryIdIsLetThrough() {
        // The connector is accepting requests before ApplicationReadyEvent fires. An
        // empty filter here would 400 every read for a shop that does exist.
        assertTrue(registry.mightContainShop(1L));
        assertTrue(registry.mightContainShop(999_999L));
        verifyNoInteractions(jdbc);
    }

    @Test
    void afterLoadingItRejectsIdsTheTableNeverHad() throws SQLException {
        tableContains(1L, 2L, 3L);

        registry.reload();

        assertTrue(registry.mightContainShop(1L));
        assertTrue(registry.mightContainShop(3L));
        assertFalse(registry.mightContainShop(999_999L));
    }

    @Test
    void capacityFollowsTheRowCountRatherThanAConstant() throws SQLException {
        // 20k rows in a filter hard-coded for 10k would sit past its design point, and
        // every false positive there is a real query.
        long[] ids = new long[20_000];
        for (int i = 0; i < ids.length; i++) ids[i] = i + 1;
        tableContains(ids);

        registry.reload();

        int falsePositives = 0;
        for (long absent = 1_000_000; absent < 1_010_000; absent++) {
            if (registry.mightContainShop(absent)) falsePositives++;
        }
        assertTrue(falsePositives < 200,
                "false-positive rate should stay near 1% with headroom, saw " + falsePositives + "/10000");
        for (long id : new long[] {1L, 10_000L, 20_000L}) {
            assertTrue(registry.mightContainShop(id), "inserted id " + id + " must never be rejected");
        }
    }

    @Test
    void anAddBeforeTheFirstLoadIsNotLostBecauseTheLoadWillSeeIt() {
        assertDoesNotThrow(() -> registry.addShop(42L));
        assertTrue(registry.mightContainShop(42L));
    }
}
