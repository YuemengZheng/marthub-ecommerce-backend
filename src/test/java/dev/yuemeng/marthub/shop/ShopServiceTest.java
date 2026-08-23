package dev.yuemeng.marthub.shop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import dev.yuemeng.marthub.cache.BloomFilterRegistry;
import dev.yuemeng.marthub.cache.CacheInvalidationListener;
import dev.yuemeng.marthub.common.BadRequestException;
import dev.yuemeng.marthub.config.MartHubProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.TaskScheduler;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Which tier answers a read, and what each miss costs the tier below it. */
class ShopServiceTest {

    private static final Shop SHOP = new Shop(1L, "North Star Coffee", "Cafe", 1299);

    private Cache<Long, Shop> l1;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ShopRepository repo;
    private BloomFilterRegistry bloom;
    private TaskScheduler scheduler;
    private ShopService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        l1 = mock(Cache.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        repo = mock(ShopRepository.class);
        bloom = mock(BloomFilterRegistry.class);
        scheduler = mock(TaskScheduler.class);
        when(redis.opsForValue()).thenReturn(values);
        when(bloom.mightContainShop(anyLong())).thenReturn(true);
        service = new ShopService(l1, redis, repo, bloom, new ObjectMapper(), scheduler, new MartHubProperties());
    }

    @Test
    void anIdTheBloomFilterHasNeverSeenNeverReachesTheCacheOrTheDatabase() {
        when(bloom.mightContainShop(999L)).thenReturn(false);

        BadRequestException e = assertThrows(BadRequestException.class, () -> service.get(999L));

        assertEquals("SHOP_NOT_FOUND", e.code());
        verifyNoInteractions(l1, repo);
        verify(redis, never()).opsForValue();
    }

    @Test
    void anL1HitCostsNeitherRedisNorTheDatabase() {
        when(l1.getIfPresent(1L)).thenReturn(SHOP);

        assertEquals(SHOP, service.get(1L));

        verify(values, never()).get(anyString());
        verifyNoInteractions(repo);
    }

    @Test
    void anL2HitPopulatesL1AndStillSkipsTheDatabase() throws Exception {
        when(l1.getIfPresent(1L)).thenReturn(null);
        when(values.get("shop:1")).thenReturn(new ObjectMapper().writeValueAsString(SHOP));

        assertEquals(SHOP, service.get(1L));

        verify(l1).put(1L, SHOP);
        verifyNoInteractions(repo);
    }

    @Test
    void aFullMissLoadsFromTheDatabaseAndBackfillsBothTiers() {
        when(l1.getIfPresent(1L)).thenReturn(null);
        when(values.get("shop:1")).thenReturn(null);
        when(repo.findById(1L)).thenReturn(Optional.of(SHOP));

        assertEquals(SHOP, service.get(1L));

        verify(l1).put(1L, SHOP);
        verify(values).set(eq("shop:1"), anyString(), any(Duration.class));
    }

    @Test
    void unreadableL2ContentIsDroppedRatherThanServed() {
        when(l1.getIfPresent(1L)).thenReturn(null);
        when(values.get("shop:1")).thenReturn("{ this is not a shop");
        when(repo.findById(1L)).thenReturn(Optional.of(SHOP));

        assertEquals(SHOP, service.get(1L), "a corrupt cache entry must not fail the read");

        verify(redis).delete("shop:1");
        verify(repo).findById(1L);
    }

    @Test
    void aBloomFalsePositiveThatIsNotInTheDatabaseIsReportedAsNotFound() {
        when(l1.getIfPresent(42L)).thenReturn(null);
        when(values.get("shop:42")).thenReturn(null);
        when(repo.findById(42L)).thenReturn(Optional.empty());

        assertEquals("SHOP_NOT_FOUND", assertThrows(BadRequestException.class, () -> service.get(42L)).code());
    }

    @Test
    void theDbOnlyPathIsWhatItSaysItIs() {
        when(repo.findById(1L)).thenReturn(Optional.of(SHOP));

        assertEquals(SHOP, service.getDbOnly(1L));

        verifyNoInteractions(l1, bloom);
        verify(redis, never()).opsForValue();
    }

    @Test
    void aWriteWithNoTransactionToHookEvictsImmediatelyRatherThanNotAtAll() {
        // No transaction is active here, so there is nothing to defer to. Evicting now is
        // worse than after a commit but far better than skipping the eviction and leaving
        // both tiers stale until their TTLs run out.
        service.update(SHOP);

        verify(repo).update(SHOP);
        verify(bloom).addShop(1L);
        verify(l1).invalidate(1L);
        verify(redis).delete("shop:1");
        verify(redis).convertAndSend(CacheInvalidationListener.CHANNEL, "1");

        ArgumentCaptor<Runnable> delayed = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(delayed.capture(), any(Instant.class));

        // The delayed eviction is what closes the read-repopulates-a-stale-value window,
        // and it has to tell the other instances too -- their L1 can have been refilled
        // with the pre-commit value in the meantime, so a local-only second eviction
        // would leave them stale.
        clearInvocations(l1, redis);
        delayed.getValue().run();
        verify(l1).invalidate(1L);
        verify(redis).delete("shop:1");
        verify(redis).convertAndSend(CacheInvalidationListener.CHANNEL, "1");
    }

    @Test
    void aWriteInsideATransactionEvictsNothingUntilTheCommitLands() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.update(SHOP);

            // The row is written, but nothing is evicted yet. An eviction at this point would
            // land before the commit, and a concurrent read could then miss both tiers, load
            // the uncommitted row, and write that stale value straight back in.
            verify(repo).update(SHOP);
            verify(bloom).addShop(1L);
            verify(l1, never()).invalidate(anyLong());
            verify(redis, never()).delete(anyString());
            verify(redis, never()).convertAndSend(anyString(), any());
            verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));

            List<TransactionSynchronization> registered =
                    TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, registered.size());
            registered.forEach(TransactionSynchronization::afterCommit);

            verify(l1).invalidate(1L);
            verify(redis).delete("shop:1");
            verify(redis).convertAndSend(CacheInvalidationListener.CHANNEL, "1");
            verify(scheduler).schedule(any(Runnable.class), any(Instant.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void theL2TtlIsJitteredSoKeysWrittenTogetherDoNotExpireTogether() {
        when(repo.findById(1L)).thenReturn(Optional.of(SHOP));

        int writes = 200;
        for (int i = 0; i < writes; i++) service.get(1L);

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(values, times(writes)).set(eq("shop:1"), anyString(), ttl.capture());

        Set<Duration> distinct = new HashSet<>(ttl.getAllValues());
        for (Duration d : distinct) {
            assertTrue(d.getSeconds() >= 600 && d.getSeconds() <= 720, "ttl out of range: " + d);
        }
        // A fixed TTL is the failure this guards against: 200 draws collapsing to one value
        // would mean every key written in a warmup loop still expires in the same instant.
        assertTrue(distinct.size() > 1, "expected jittered TTLs, every write used " + distinct);
    }
}
