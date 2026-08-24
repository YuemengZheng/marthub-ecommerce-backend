package dev.yuemeng.marthub.benchmark;

import dev.yuemeng.marthub.auth.SessionUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import dev.yuemeng.marthub.flashsale.EligibilityService;
import dev.yuemeng.marthub.flashsale.FlashSaleMetrics;
import dev.yuemeng.marthub.flashsale.FlashSaleService;
import dev.yuemeng.marthub.flashsale.OrderService;
import dev.yuemeng.marthub.flashsale.RedisRateLimiter;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import dev.yuemeng.marthub.shop.Shop;
import dev.yuemeng.marthub.shop.ShopService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Reproduces the numbers in TEST_STATUS.md. These routes are excluded from the login
 * interceptor, so rather than registering them and refusing each call, the whole
 * controller is only created when {@code marthub.benchmark.enabled} is true. It
 * defaults to false, which means that on a normal boot these paths do not exist at
 * all and return 404 — there is no handler to reach.
 */
@RestController
@RequestMapping("/internal/benchmark")
@ConditionalOnProperty(prefix = "marthub.benchmark", name = "enabled", havingValue = "true")
public class BenchmarkController {
    private final ShopService shops;
    private final FlashSaleMetrics metrics;
    private final OrderService orders;
    private final FlashSaleService flashSales;
    private final RedisRateLimiter limiter;
    private final EligibilityService eligibility;
    private final Cache<Long, Shop> l1;

    public BenchmarkController(ShopService shops, FlashSaleMetrics metrics,
                               OrderService orders, FlashSaleService flashSales, RedisRateLimiter limiter,
                               EligibilityService eligibility,
                               Cache<Long, Shop> l1) {
        this.l1 = l1;
        this.shops = shops;
        this.metrics = metrics;
        this.orders = orders;
        this.flashSales = flashSales;
        this.limiter = limiter;
        this.eligibility = eligibility;
    }

    /**
     * Caffeine's own counters. An L1 hit is a request that never reached Redis, so
     * this is also the measurement of how much Redis traffic the local tier absorbs.
     * Per-process, so read it from one instance rather than through the load balancer.
     */
    @GetMapping("/cache/l1-stats")
    public Map<String, Object> l1Stats() {
        CacheStats st = l1.stats();
        return Map.of(
                "hitCount", st.hitCount(),
                "missCount", st.missCount(),
                "hitRate", st.hitRate(),
                "estimatedSize", l1.estimatedSize());
    }

    @GetMapping("/shop/{id}/db")
    public Shop db(@PathVariable long id) { return shops.getDbOnly(id); }

    @GetMapping("/shop/{id}/cached")
    public Shop cached(@PathVariable long id) { return shops.get(id); }

    @PostMapping("/metrics/reset")
    public void reset() { metrics.reset(); }

    @GetMapping("/metrics")
    public Map<String, Long> metrics() {
        return Map.of("orderProcessorEntries", metrics.orderProcessorEntries(), "preOrderRejections", metrics.preOrderRejections());
    }

    @PostMapping("/flash-sale/baseline-invalid")
    public ResponseEntity<Void> baselineInvalid() {
        orders.baselineInvalidAttempt();
        return ResponseEntity.badRequest().build();
    }

    @PostMapping("/flash-sale/rate/reset")
    public void resetRate(@RequestParam long itemId, @RequestParam(required = false) Long userId) {
        limiter.reset(itemId);
        if (userId != null) limiter.resetUser(itemId, userId);
        // A sell-out recorded by an earlier run would otherwise short-circuit the next one before
        // it measured anything.
        eligibility.clearSoldOut(itemId);
        metrics.reset();
    }

    @PostMapping("/flash-sale/admission")
    public ResponseEntity<Void> admission(@RequestParam long itemId,
                                          @RequestHeader("X-Eligibility-Token") String token,
                                          @AuthenticationPrincipal SessionUser user) {
        // These routes are permitAll so the harness can reset counters without a session, but
        // admission still needs a caller: the point of the measurement is the real admission path.
        if (user == null) return ResponseEntity.status(401).build();
        flashSales.benchmarkAdmission(itemId, user, token);
        return ResponseEntity.noContent().build();
    }
}
