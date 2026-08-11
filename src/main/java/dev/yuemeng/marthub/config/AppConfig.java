package dev.yuemeng.marthub.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.yuemeng.marthub.shop.Shop;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(MartHubProperties.class)
public class AppConfig {
    @Bean
    Cache<Long, Shop> shopL1Cache(MartHubProperties props) {
        return Caffeine.newBuilder()
                .maximumSize(props.getCache().getL1MaxSize())
                .expireAfterWrite(Duration.ofSeconds(props.getCache().getL1TtlSeconds()))
                .recordStats()
                .build();
    }
}
