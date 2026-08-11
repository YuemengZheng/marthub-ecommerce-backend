package dev.yuemeng.marthub.cache;

import com.github.benmanes.caffeine.cache.Cache;
import dev.yuemeng.marthub.shop.Shop;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
public class CacheInvalidationListener implements MessageListener {
    public static final String CHANNEL = "marthub:shop-cache-invalidate";
    private final Cache<Long, Shop> l1;
    public CacheInvalidationListener(Cache<Long, Shop> l1) { this.l1 = l1; }
    @Override public void onMessage(Message message, byte[] pattern) {
        try { l1.invalidate(Long.parseLong(message.toString())); }
        catch (NumberFormatException ignored) { }
    }
}
