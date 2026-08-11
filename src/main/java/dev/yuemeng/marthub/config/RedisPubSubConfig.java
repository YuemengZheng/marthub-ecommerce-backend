package dev.yuemeng.marthub.config;

import dev.yuemeng.marthub.cache.CacheInvalidationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisPubSubConfig {
    @Bean
    RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory factory,
                                                                 CacheInvalidationListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(listener, new ChannelTopic(CacheInvalidationListener.CHANNEL));
        return container;
    }
}
