package dev.yuemeng.marthub.cache;

import com.github.benmanes.caffeine.cache.Cache;
import dev.yuemeng.marthub.shop.Shop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Each instance keeps its own Caffeine L1, so a write on one instance has to reach the
 * other two over pub/sub or they serve a stale shop until their TTL expires.
 */
class CacheInvalidationListenerTest {

    private Cache<Long, Shop> l1;
    private CacheInvalidationListener listener;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        l1 = mock(Cache.class);
        listener = new CacheInvalidationListener(l1);
    }

    private DefaultMessage message(String body) {
        return new DefaultMessage(CacheInvalidationListener.CHANNEL.getBytes(StandardCharsets.UTF_8),
                body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aPublishedIdEvictsThatEntryFromTheLocalL1() {
        listener.onMessage(message("42"), null);

        verify(l1).invalidate(42L);
    }

    @Test
    void theMessageBodyIsWhatCarriesTheId() {
        // The listener reads the id off Message#toString, so this pins that assumption
        // rather than leaving it to be discovered in production.
        assertEquals("42", message("42").toString());
    }

    @Test
    void aMalformedMessageIsIgnoredInsteadOfKillingTheSubscriber() {
        assertDoesNotThrow(() -> listener.onMessage(message("not-a-number"), null));

        verify(l1, never()).invalidate(anyLong());
    }
}
