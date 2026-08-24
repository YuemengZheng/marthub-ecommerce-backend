package dev.yuemeng.marthub.flashsale;

import dev.yuemeng.marthub.auth.SessionUser;
import dev.yuemeng.marthub.common.BadRequestException;
import dev.yuemeng.marthub.common.ConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * The write path, as a sequence of gates that each answer one question.
 *
 * <pre>
 *   (1) already bought?   -> return that order          replay, not an error
 *   (2) sold out?         -> SOLD_OUT                   terminal state
 *   (3) token valid?      -> INVALID_TOKEN              eligibility
 *   (4) acquire lease     -> IN_PROGRESS                one attempt at a time, per user
 *   (5) per-item bucket   -> RATE_LIMITED               total throughput
 *   (6) UPDATE + INSERT, commit                         uq_user_item decides correctness
 *   (7) record the order id
 *   (8) revoke the token
 *   (9) release the lease
 * </pre>
 *
 * <p>Six mechanisms with no overlap in what they defend: the bought marker gives idempotent replay,
 * the sold-out flag a terminal answer, the token and its gate say who may compete at all, the lease
 * bounds concurrency to one per user, the bucket bounds total rate, and {@code uq_user_item} is the
 * only thing that makes any of it correct.
 *
 * <p>There is no per-user rate limit here any more. Bounding how many requests one caller may send
 * belongs at the edge -- nginx keys a {@code limit_req} zone on the session token -- not in the
 * admission path, where it was standing in for the concurrency limit that (4) now states directly.
 */
@Service
public class FlashSaleService {
    private final EligibilityService eligibility;
    private final ProcessingGuard guard;
    private final RedisRateLimiter limiter;
    private final OrderService orders;
    private final FlashSaleMetrics metrics;

    public FlashSaleService(EligibilityService eligibility, ProcessingGuard guard,
                            RedisRateLimiter limiter, OrderService orders, FlashSaleMetrics metrics) {
        this.eligibility = eligibility;
        this.guard = guard;
        this.limiter = limiter;
        this.orders = orders;
        this.metrics = metrics;
    }

    public String issueToken(long itemId, SessionUser user) {
        return eligibility.issue(itemId, user);
    }

    public OrderResult placeOrder(long itemId, SessionUser user, String token) {
        // (1) Ahead of the sold-out check on purpose. A buyer who already succeeded must get their
        // order back even after the item runs out; the other order would answer them SOLD_OUT and
        // lose an order that exists.
        Long alreadyBought = eligibility.boughtOrderId(itemId, user.id());
        if (alreadyBought != null) return new OrderResult(alreadyBought, true);

        admit(itemId, user, token);                                  // (2) (3)

        String lease = guard.acquire(itemId, user.id());             // (4)
        if (lease == null) {
            metrics.rejectedBeforeOrder();
            throw new ConflictException("IN_PROGRESS", "an order attempt for this item is already in flight");
        }
        try {
            // (5) After the lease, so a duplicate concurrent attempt from one user cannot spend a
            // token out of the budget the whole crowd shares. That was the original reason the
            // per-user check came first, and it survives the per-user limiter being removed.
            if (!limiter.allow(itemId)) {
                metrics.rejectedBeforeOrder();
                throw new BadRequestException("RATE_LIMITED", "rate limit exceeded");
            }
            return order(itemId, user);                              // (6) (7) (8)
        } finally {
            guard.release(itemId, user.id(), lease);                 // (9)
        }
    }

    private OrderResult order(long itemId, SessionUser user) {
        long orderId;
        try {
            orderId = orders.create(itemId, user.id());
        } catch (BadRequestException e) {
            // Learning that stock is gone is worth remembering: without it every later caller
            // repeats the same journey to reach the same answer. Narrowed to the sell-out code --
            // marking the item sold out on any order failure would turn one caller's duplicate into
            // an outage for the stock that remains.
            if ("SOLD_OUT".equals(e.code())) eligibility.markSoldOut(itemId);
            throw e;
        } catch (DuplicateKeyException e) {
            return replay(itemId, user, e);
        }
        eligibility.markBought(itemId, user.id(), orderId);          // (7)
        eligibility.revokeToken(itemId, user.id());                  // (8)
        return new OrderResult(orderId, false);
    }

    /**
     * Reached when the constraint refuses the insert, which happens for two different reasons: two
     * requests cleared admission together, or a previous attempt committed and died before it could
     * record the order id. The second is why this exists at all -- Redis is a cache, so the window
     * between commit and (7) is only survivable if the id can be recovered from the row itself.
     *
     * <p>The failed transaction has already rolled back by the time this runs, so the stock it
     * decremented is back on the shelf.
     */
    private OrderResult replay(long itemId, SessionUser user, DuplicateKeyException e) {
        Long durable = orders.findExistingOrder(itemId, user.id());
        if (durable == null) throw e;   // the constraint refused something else; do not invent an answer
        eligibility.markBought(itemId, user.id(), durable);
        eligibility.revokeToken(itemId, user.id());
        return new OrderResult(durable, true);
    }

    /**
     * Cheapest and most final answers first.
     *
     * <p>A finished sale is a terminal state, not a caller going too fast. Letting a rate limiter
     * absorb that traffic conflated the two: the rejection said RATE_LIMITED, which tells a client
     * to back off and return, and the requests it did admit still reached the stock row.
     */
    private void admit(long itemId, SessionUser user, String token) {
        if (eligibility.soldOut(itemId)) {                           // (2)
            metrics.rejectedBeforeOrder();
            throw new BadRequestException("SOLD_OUT", "sold out");
        }
        if (!eligibility.valid(itemId, user.id(), token)) {          // (3)
            metrics.rejectedBeforeOrder();
            throw new BadRequestException("INVALID_TOKEN", "invalid eligibility token");
        }
    }

    /**
     * Runs the production gates without mutating stock or orders, so the benchmark measures the
     * admission layer rather than the database. Every gate that does not write is included --
     * leaving the lease out would report a rate-limiter figure the real path never produces.
     */
    public void benchmarkAdmission(long itemId, SessionUser user, String token) {
        if (eligibility.boughtOrderId(itemId, user.id()) != null) return;
        admit(itemId, user, token);
        String lease = guard.acquire(itemId, user.id());
        if (lease == null) {
            metrics.rejectedBeforeOrder();
            throw new ConflictException("IN_PROGRESS", "an order attempt for this item is already in flight");
        }
        try {
            if (!limiter.allow(itemId)) {
                metrics.rejectedBeforeOrder();
                throw new BadRequestException("RATE_LIMITED", "rate limit exceeded");
            }
            metrics.enteredOrderProcessor();
        } finally {
            guard.release(itemId, user.id(), lease);
        }
    }
}
