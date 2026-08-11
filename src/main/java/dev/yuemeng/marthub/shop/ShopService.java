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

import java.time.Duration;
import java.time.Instant;

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
    @Transactional
    public void update(Shop shop) {
        repo.update(shop);
        bloom.addShop(shop.id());
        evict(shop.id());
        scheduler.schedule(() -> evict(shop.id()), Instant.now().plusMillis(props.getCache().getDelayedEvictionMs()));
    }
    public void evict(long id) {
        l1.invalidate(id);
        redis.delete(key(id));
        redis.convertAndSend(CacheInvalidationListener.CHANNEL, Long.toString(id));
    }
    private void writeCaches(Shop shop) {
        l1.put(shop.id(), shop);
        try { redis.opsForValue().set(key(shop.id()), mapper.writeValueAsString(shop), Duration.ofSeconds(props.getCache().getL2TtlSeconds())); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    private String key(long id){ return "shop:"+id; }
}
