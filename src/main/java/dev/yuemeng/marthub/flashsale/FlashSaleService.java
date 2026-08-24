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
        long id;
        try {
            id = orders.create(itemId, user.id());
        } catch (BadRequestException e) {
            // Learning that stock is gone is worth remembering: without it every later caller
            // repeats the same journey to reach the same answer.
            if ("SOLD_OUT".equals(e.code())) eligibility.markSoldOut(itemId);
            throw e;
        }
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
     * Four gates, ordered so that the cheapest and most final answers come first.
     *
     * <p>The per-user bucket comes before the per-item one on purpose: a rejection there must not
     * spend a token from the bucket everyone shares. Without that ordering a single caller in a
     * retry loop would consume the item's whole allowance and starve every other user.
     *
     * <p>Both limits report the same {@code RATE_LIMITED} code. Which bucket refused is an
     * operational detail, not something a client can act on differently. {@code SOLD_OUT} is
     * deliberately not folded in with them -- it says the sale is over, not slow down.
     */
    private void admit(long itemId, SessionUser user, String token) {
        // Cheapest and most final answer first. A finished sale is a terminal state, not a caller
        // going too fast, and letting the rate limiter absorb that traffic conflated the two: the
        // rejection said RATE_LIMITED, and the requests it did admit still reached the stock row.
        if (eligibility.soldOut(itemId)) {
            metrics.rejectedBeforeOrder();
            throw new BadRequestException("SOLD_OUT", "sold out");
        }
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
