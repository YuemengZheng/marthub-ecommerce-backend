package dev.yuemeng.marthub.shop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import dev.yuemeng.marthub.cache.BloomFilterRegistry;
import dev.yuemeng.marthub.cache.CacheInvalidationListener;
import dev.yuemeng.marthub.common.BadRequestException;
import dev.yuemeng.marthub.config.MartHubProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ShopService {
    private final Cache<Long,Shop> l1;
    private final StringRedisTemplate redis;
    private final ShopRepository repo;
    private final BloomFilterRegistry bloom;
    private final ObjectMapper mapper;
    private final TaskScheduler scheduler;
    private final MartHubProperties props;
    public ShopService(Cache<Long,Shop> l1, StringRedisTemplate redis, ShopRepository repo, BloomFilterRegistry bloom,
                       ObjectMapper mapper, TaskScheduler scheduler, MartHubProperties props) {
        this.l1=l1; this.redis=redis; this.repo=repo; this.bloom=bloom; this.mapper=mapper; this.scheduler=scheduler; this.props=props;
    }
    public Shop get(long id) {
        if (!bloom.mightContainShop(id)) throw new BadRequestException("SHOP_NOT_FOUND", "shop not found");
        Shop hit = l1.getIfPresent(id);
        if (hit != null) return hit;
        String key = key(id);
        String json = redis.opsForValue().get(key);
        if (json != null) {
            try { Shop shop=mapper.readValue(json, Shop.class); l1.put(id,shop); return shop; }
            catch (JsonProcessingException e) { redis.delete(key); }
        }
        Shop shop = repo.findById(id).orElseThrow(() -> new BadRequestException("SHOP_NOT_FOUND","shop not found"));
        writeCaches(shop);
        return shop;
    }
    public Shop getDbOnly(long id) { return repo.findById(id).orElseThrow(() -> new BadRequestException("SHOP_NOT_FOUND","shop not found")); }
    /**
     * Write, then evict twice -- both evictions after the commit.
     *
     * <p>An earlier version evicted inside the transaction. That eviction landed
     * <em>before</em> the commit, so a concurrent read could miss both tiers, load the row
     * the transaction had not committed yet, and write that stale value back. The delay
     * existed to cover a window this method created for itself. Registering the eviction
     * with {@link TransactionSynchronization#afterCommit()} removes that window: nothing is
     * evicted until the new row is durable, so no reader can refill from an uncommitted read
     * that started after the eviction.
     *
     * <p>A narrower window survives and is what the delayed second eviction is for: a reader
     * that loaded the old row <em>before</em> the commit can still write it into the caches
     * <em>after</em> the first eviction. That refill is bounded by how long a read takes, not
     * by the transaction, which is why the delay can be short.
     *
     * <p>Both evictions publish, because the stale refill can have happened on any instance,
     * not just this one. Two known limits: Redis pub/sub is fire-and-forget, so an instance
     * that is restarting or partitioned never sees the message -- the short L1 TTL, not the
     * broadcast, is what bounds staleness there. And the delayed task lives in this process's
     * scheduler, so if the process dies inside the delay the second eviction is lost.
     */
    @Transactional
    public void update(Shop shop) {
        repo.update(shop);
        bloom.addShop(shop.id());
        evictAfterCommit(shop.id());
    }
    /**
     * Defer eviction to after the commit. Falls back to evicting immediately when there is no
     * transaction to hook (a direct call that bypassed the proxy), because dropping the
     * eviction entirely would leave the caches stale indefinitely.
     */
    private void evictAfterCommit(long id) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { evictTwice(id); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { evictTwice(id); }
        });
    }
    private void evictTwice(long id) {
        evict(id);
        scheduler.schedule(() -> evict(id), Instant.now().plusMillis(props.getCache().getDelayedEvictionMs()));
    }
    public void evict(long id) {
        l1.invalidate(id);
        redis.delete(key(id));
        redis.convertAndSend(CacheInvalidationListener.CHANNEL, Long.toString(id));
    }
    private void writeCaches(Shop shop) {
        l1.put(shop.id(), shop);
        try { redis.opsForValue().set(key(shop.id()), mapper.writeValueAsString(shop), l2Ttl()); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    /**
     * L2 TTL with jitter. Keys created together -- a warmup loop, or the refill burst after a
     * deploy -- would otherwise share an expiry instant and all miss at once. The jitter
     * spreads those expiries over a window instead.
     */
    private Duration l2Ttl() {
        long base = props.getCache().getL2TtlSeconds();
        long jitter = props.getCache().getL2TtlJitterSeconds();
        return Duration.ofSeconds(jitter <= 0 ? base : base + ThreadLocalRandom.current().nextLong(jitter + 1));
    }
    private String key(long id){ return "shop:"+id; }
}
