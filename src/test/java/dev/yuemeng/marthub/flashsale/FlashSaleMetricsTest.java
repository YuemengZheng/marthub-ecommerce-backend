package dev.yuemeng.marthub.flashsale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * These counters were once JVM-local. Behind a load balancer the reset and the read
 * landed on different instances and the numbers were nonsense, so they live in Redis
 * now; these tests pin that down.
 */
class FlashSaleMetricsTest {

    private static final String ENTRIES = "bench:fs:orderProcessorEntries";
    private static final String REJECTIONS = "bench:fs:preOrderRejections";

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private FlashSaleMetrics metrics;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        metrics = new FlashSaleMetrics(redis);
    }

    @Test
    void countingHappensInRedisSoEveryInstanceAddsToTheSameTotal() {
        metrics.enteredOrderProcessor();
        metrics.rejectedBeforeOrder();

        verify(values).increment(ENTRIES);
        verify(values).increment(REJECTIONS);
    }

    @Test
    void anUnsetCounterReadsAsZeroRatherThanBlowingUp() {
        when(values.get(ENTRIES)).thenReturn(null);

        assertEquals(0L, metrics.orderProcessorEntries());
    }

    @Test
    void countersAreReadBackAsNumbers() {
        when(values.get(ENTRIES)).thenReturn("50");
        when(values.get(REJECTIONS)).thenReturn("150");

        assertEquals(50L, metrics.orderProcessorEntries());
        assertEquals(150L, metrics.preOrderRejections());
    }

    @Test
    void resetClearsBothCountersInOneCall() {
        metrics.reset();

        verify(redis).delete(List.of(ENTRIES, REJECTIONS));
    }
}
