package dev.yuemeng.marthub.flashsale;

import dev.yuemeng.marthub.auth.SessionUser;
import dev.yuemeng.marthub.common.BadRequestException;
import org.springframework.stereotype.Service;

@Service
public class FlashSaleService {
    private final EligibilityService eligibility;
    private final RedisRateLimiter limiter;
    private final OrderService orders;
    private final FlashSaleMetrics metrics;

    public FlashSaleService(EligibilityService eligibility, RedisRateLimiter limiter, OrderService orders, FlashSaleMetrics metrics) {
        this.eligibility = eligibility;
        this.limiter = limiter;
        this.orders = orders;
        this.metrics = metrics;
    }

    public String issueToken(long itemId, SessionUser user) {
        return eligibility.issue(itemId, user);
    }

    public long placeOrder(long itemId, SessionUser user, String token) {
        admit(itemId, user, token);
        long id = orders.create(itemId, user.id());
        eligibility.markBought(itemId, user.id());
        return id;
    }

    /**
     * Runs the exact production admission checks without mutating stock/orders.
     * Used only by the benchmark endpoint to isolate rate-limiter behavior.
     */
    public void benchmarkAdmission(long itemId, SessionUser user, String token) {
        admit(itemId, user, token);
        metrics.enteredOrderProcessor();
    }

    /**
     * Three gates, cheapest and narrowest first.
     *
     * <p>The per-user bucket comes before the per-item one on purpose: a rejection there must not
     * spend a token from the bucket everyone shares. Without that ordering one caller retrying a
     * sold-out item -- its token stays valid, because markBought only runs after a successful
     * order -- would consume the item's whole allowance and starve every other user.
     *
     * <p>Both limits report the same {@code RATE_LIMITED} code. Which bucket refused is an
     * operational detail, not something a client can act on differently.
     */
    private void admit(long itemId, SessionUser user, String token) {
        if (!eligibility.valid(itemId, user.id(), token)) {
            metrics.rejectedBeforeOrder();
            throw new BadRequestException("INVALID_TOKEN", "invalid eligibility token");
        }
        if (!limiter.allowUser(itemId, user.id())) {
            metrics.rejectedBeforeOrder();
            throw new BadRequestException("RATE_LIMITED", "per-user rate limit exceeded");
        }
        if (!limiter.allow(itemId)) {
            metrics.rejectedBeforeOrder();
            throw new BadRequestException("RATE_LIMITED", "rate limit exceeded");
        }
    }
}
