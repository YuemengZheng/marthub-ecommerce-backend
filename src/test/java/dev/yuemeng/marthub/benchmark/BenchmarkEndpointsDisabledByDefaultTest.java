package dev.yuemeng.marthub.benchmark;

import com.github.benmanes.caffeine.cache.Caffeine;
import dev.yuemeng.marthub.flashsale.FlashSaleMetrics;
import dev.yuemeng.marthub.flashsale.FlashSaleService;
import dev.yuemeng.marthub.flashsale.OrderService;
import dev.yuemeng.marthub.flashsale.RedisRateLimiter;
import dev.yuemeng.marthub.shop.Shop;
import dev.yuemeng.marthub.shop.ShopService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The benchmark routes are deliberately excluded from the login interceptor, which
 * would be a hole if they were always registered. They are not: the controller is
 * conditional, so on a default boot there is no bean and therefore no route.
 */
class BenchmarkEndpointsDisabledByDefaultTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(ShopService.class, () -> mock(ShopService.class))
            .withBean(FlashSaleMetrics.class, () -> mock(FlashSaleMetrics.class))
            .withBean(OrderService.class, () -> mock(OrderService.class))
            .withBean(FlashSaleService.class, () -> mock(FlashSaleService.class))
            .withBean(RedisRateLimiter.class, () -> mock(RedisRateLimiter.class))
            .withBean(com.github.benmanes.caffeine.cache.Cache.class,
                    () -> Caffeine.newBuilder().recordStats().<Long, Shop>build())
            .withUserConfiguration(BenchmarkController.class);

    @Test
    void theControllerIsAbsentUnlessItIsAskedFor() {
        context.run(ctx -> assertThat(ctx).doesNotHaveBean(BenchmarkController.class));
    }

    @Test
    void anExplicitFalseAlsoKeepsItOut() {
        context.withPropertyValues("marthub.benchmark.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(BenchmarkController.class));
    }

    @Test
    void itIsRegisteredOnlyWhenTheFlagIsOn() {
        context.withPropertyValues("marthub.benchmark.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(BenchmarkController.class));
    }
}
