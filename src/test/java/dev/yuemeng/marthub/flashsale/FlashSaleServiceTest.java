package dev.yuemeng.marthub.flashsale;

import dev.yuemeng.marthub.auth.SessionUser;
import dev.yuemeng.marthub.common.BadRequestException;
import dev.yuemeng.marthub.common.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The whole point of the admission stage is what it stops. Every rejection test here
 * asserts {@code verifyNoInteractions(orders)} — that is the counter in the benchmark,
 * expressed as a unit test.
 */
class FlashSaleServiceTest {

    private static final long ITEM = 101L;
    private static final SessionUser USER = new SessionUser(2L, "LoadTest");
    private static final String LEASE = "lease-abc";

    private EligibilityService eligibility;
    private ProcessingGuard guard;
    private RedisRateLimiter limiter;
    private OrderService orders;
    private FlashSaleMetrics metrics;
    private FlashSaleService service;

    @BeforeEach
    void setUp() {
        eligibility = mock(EligibilityService.class);
        guard = mock(ProcessingGuard.class);
        limiter = mock(RedisRateLimiter.class);
        orders = mock(OrderService.class);
        metrics = mock(FlashSaleMetrics.class);
        service = new FlashSaleService(eligibility, guard, limiter, orders, metrics);
        // Defaults for "nothing is in the way"; the tests that care about one gate override it.
        // Without this an unstubbed acquire returns null and every test would be refused by the
        // lease for the wrong reason.
        when(guard.acquire(anyLong(), anyLong())).thenReturn(LEASE);
        when(limiter.allow(anyLong())).thenReturn(true);
        // Mockito's default for a boxed Long is 0L, not null -- so without this every test would
        // be answered at the first gate as a replay of order 0 and nothing further would run.
        when(eligibility.boughtOrderId(anyLong(), anyLong())).thenReturn(null);
    }

    // ── (1) already bought ────────────────────────────────────────────────────────────────────

    @Test
    void aBuyerWhoRepeatsTheirRequestGetsTheirOrderBackRatherThanAnError() {
        when(eligibility.boughtOrderId(ITEM, USER.id())).thenReturn(9001L);

        OrderResult result = service.placeOrder(ITEM, USER, "whatever-they-still-have");

        assertEquals(9001L, result.orderId());
        assertTrue(result.replayed());
        // A client whose response was lost on the network has an order. Telling it "invalid token"
        // would be both unhelpful and untrue, and re-running the order path would cost a row lock
        // to arrive back at the same id.
        verifyNoInteractions(orders, limiter, guard);
    }

    @Test
    void aBuyersReplayIsAnsweredEvenAfterTheItemHasSoldOut() {
        when(eligibility.boughtOrderId(ITEM, USER.id())).thenReturn(9001L);
        when(eligibility.soldOut(ITEM)).thenReturn(true);

        // This is why the bought check has to come first. The other order refuses a caller who
        // already owns an order, and the order they own is what they were asking about.
        assertEquals(9001L, service.placeOrder(ITEM, USER, "stale-token").orderId());
        verify(eligibility, never()).soldOut(anyLong());
    }

    // ── (2) sold out ──────────────────────────────────────────────────────────────────────────

    @Test
    void aFinishedSaleIsRefusedBeforeAnythingElseIsAsked() {
        when(eligibility.soldOut(ITEM)).thenReturn(true);

        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.placeOrder(ITEM, USER, "a-perfectly-valid-token"));

        // The reason matters as much as the refusal: a caller told RATE_LIMITED will back off and
        // try again, and a caller told SOLD_OUT has no reason to come back at all.
        assertEquals("SOLD_OUT", e.code());
        verifyNoInteractions(orders, limiter, guard);
        verify(eligibility, never()).valid(anyLong(), anyLong(), anyString());
    }

    // ── (3) eligibility ───────────────────────────────────────────────────────────────────────

    @Test
    void anInvalidTokenIsRejectedWithoutEnteringOrderProcessing() {
        when(eligibility.valid(ITEM, USER.id(), "definitely-invalid")).thenReturn(false);

        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.placeOrder(ITEM, USER, "definitely-invalid"));

        assertEquals("INVALID_TOKEN", e.code());
        verifyNoInteractions(orders);
        verify(metrics).rejectedBeforeOrder();
        verify(metrics, never()).enteredOrderProcessor();
    }

    @Test
    void anInvalidTokenIsRejectedBeforeTheLeaseOrTheLimiter() {
        when(eligibility.valid(anyLong(), anyLong(), anyString())).thenReturn(false);

        assertThrows(BadRequestException.class, () -> service.placeOrder(ITEM, USER, "nope"));

        verifyNoInteractions(limiter, guard);
    }

    // ── (4) one attempt at a time, per user ───────────────────────────────────────────────────

    @Test
    void aSecondConcurrentAttemptFromOneUserIsRefusedWithoutSpendingTheSharedBudget() {
        when(eligibility.valid(ITEM, USER.id(), "good-token")).thenReturn(true);
        when(guard.acquire(ITEM, USER.id())).thenReturn(null);

        ConflictException e = assertThrows(ConflictException.class,
                () -> service.placeOrder(ITEM, USER, "good-token"));

        assertEquals("IN_PROGRESS", e.code());
        // This is the property the deleted per-user rate limiter existed for, and it has to survive
        // the deletion: a duplicate attempt from one caller must not consume a token out of the
        // budget the whole crowd is waiting on. Which is why the lease is acquired before the
        // bucket, not after.
        verify(limiter, never()).allow(anyLong());
        verifyNoInteractions(orders);
        verify(metrics).rejectedBeforeOrder();
        verify(metrics, never()).enteredOrderProcessor();
    }

    @Test
    void aRefusedLeaseIsNotReleased() {
        when(eligibility.valid(ITEM, USER.id(), "good-token")).thenReturn(true);
        when(guard.acquire(ITEM, USER.id())).thenReturn(null);

        assertThrows(ConflictException.class, () -> service.placeOrder(ITEM, USER, "good-token"));

        // Releasing on this path would hand away the exclusivity of whoever actually holds it.
        verify(guard, never()).release(anyLong(), anyLong(), anyString());
    }

    // ── (5) total throughput ──────────────────────────────────────────────────────────────────

    @Test
    void aRateLimitedRequestIsRejectedWithoutEnteringOrderProcessing() {
        when(eligibility.valid(ITEM, USER.id(), "good-token")).thenReturn(true);
        when(limiter.allow(ITEM)).thenReturn(false);

        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.placeOrder(ITEM, USER, "good-token"));

        assertEquals("RATE_LIMITED", e.code());
        verifyNoInteractions(orders);
        verify(metrics).rejectedBeforeOrder();
    }

    // ── (9) the lease always comes back ───────────────────────────────────────────────────────

    @Test
    void theLeaseIsReleasedOnEveryPathThatAcquiredIt() {
        when(eligibility.valid(ITEM, USER.id(), "good-token")).thenReturn(true);

        when(limiter.allow(ITEM)).thenReturn(false);
        assertThrows(BadRequestException.class, () -> service.placeOrder(ITEM, USER, "good-token"));

        when(limiter.allow(ITEM)).thenReturn(true);
        when(orders.create(ITEM, USER.id())).thenThrow(new BadRequestException("SOLD_OUT", "sold out"));
        assertThrows(BadRequestException.class, () -> service.placeOrder(ITEM, USER, "good-token"));

        // A lease held to its TTL locks the user out of retrying for as long as it lives, so the
        // release cannot sit on the success path only.
        verify(guard, times(2)).release(ITEM, USER.id(), LEASE);
    }

    // ── (6)(7)(8) the happy path, in order ────────────────────────────────────────────────────

    @Test
    void anAdmittedRequestRecordsTheOrderBeforeItRevokesTheToken() {
        when(eligibility.valid(ITEM, USER.id(), "good-token")).thenReturn(true);
        when(orders.create(ITEM, USER.id())).thenReturn(9001L);

        OrderResult result = service.placeOrder(ITEM, USER, "good-token");

        assertEquals(9001L, result.orderId());
        assertFalse(result.replayed());
        // Crashing between these two has to leave the recoverable state, not the expensive one: a
        // live token beside a recorded purchase is answered by the first gate for free, where the
        // reverse leaves neither and costs a row lock to work out.
        InOrder inOrder = inOrder(orders, eligibility, guard);
        inOrder.verify(orders).create(ITEM, USER.id());
        inOrder.verify(eligibility).markBought(ITEM, USER.id(), 9001L);
        inOrder.verify(eligibility).revokeToken(ITEM, USER.id());
        inOrder.verify(guard).release(ITEM, USER.id(), LEASE);
    }

    // ── the constraint, and what happens after it fires ───────────────────────────────────────

    @Test
    void aConstraintViolationIsResolvedFromTheDatabaseRatherThanReportedAsAFailure() {
        when(eligibility.valid(ITEM, USER.id(), "good-token")).thenReturn(true);
        when(orders.create(ITEM, USER.id())).thenThrow(new DuplicateKeyException("uq_user_item"));
        when(orders.findExistingOrder(ITEM, USER.id())).thenReturn(9002L);

        OrderResult result = service.placeOrder(ITEM, USER, "good-token");

        // This is what makes the commit-then-crash window survivable: Redis is a cache, so the id
        // has to be recoverable from the row that the constraint is complaining about.
        assertEquals(9002L, result.orderId());
        assertTrue(result.replayed());
        verify(eligibility).markBought(ITEM, USER.id(), 9002L);
        verify(eligibility).revokeToken(ITEM, USER.id());
    }

    @Test
    void aConstraintViolationWithNoOrderBehindItIsNotAnsweredWithAnInventedId() {
        when(eligibility.valid(ITEM, USER.id(), "good-token")).thenReturn(true);
        when(orders.create(ITEM, USER.id())).thenThrow(new DuplicateKeyException("some other index"));
        when(orders.findExistingOrder(ITEM, USER.id())).thenReturn(null);

        assertThrows(DuplicateKeyException.class, () -> service.placeOrder(ITEM, USER, "good-token"));

        verify(eligibility, never()).markBought(anyLong(), anyLong(), anyLong());
    }

    @Test
    void aDoubleBuyDoesNotCloseTheSaleForEveryoneElse() {
        when(eligibility.valid(ITEM, USER.id(), "good-token")).thenReturn(true);
        when(orders.create(ITEM, USER.id())).thenThrow(new DuplicateKeyException("uq_user_item"));
        when(orders.findExistingOrder(ITEM, USER.id())).thenReturn(9002L);

        service.placeOrder(ITEM, USER, "good-token");

        // Marking the item sold out here would turn one user's duplicate into an outage for the
        // remaining stock -- which is why the catch is narrowed to the sell-out code, not to
        // whatever the order path happens to throw.
        verify(eligibility, never()).markSoldOut(anyLong());
    }

    // ── sell-out, observed at the database ────────────────────────────────────────────────────

    @Test
    void theFirstCallerToFindStockGoneIsTheLastToPayForIt() {
        when(eligibility.valid(ITEM, USER.id(), "valid")).thenReturn(true);
        when(orders.create(ITEM, USER.id())).thenThrow(new BadRequestException("SOLD_OUT", "sold out"));

        assertThrows(BadRequestException.class, () -> service.placeOrder(ITEM, USER, "valid"));

        // Someone has to discover the sell-out at the database. Writing it down is what stops
        // everyone behind them discovering it the same expensive way.
        verify(eligibility).markSoldOut(ITEM);
        verify(eligibility, never()).markBought(anyLong(), anyLong(), anyLong());
    }

    // ── the benchmark path is the same path ───────────────────────────────────────────────────

    @Test
    void theBenchmarkAdmissionPathRunsTheSameChecksWithoutTouchingStock() {
        when(eligibility.valid(ITEM, USER.id(), "good-token")).thenReturn(true);

        service.benchmarkAdmission(ITEM, USER, "good-token");

        verify(metrics).enteredOrderProcessor();
        verify(guard).acquire(ITEM, USER.id());
        verify(guard).release(ITEM, USER.id(), LEASE);
        verifyNoInteractions(orders);
    }

    @Test
    void theBenchmarkAdmissionPathIsNotAWayAroundTheGate() {
        when(eligibility.valid(anyLong(), anyLong(), anyString())).thenReturn(false);

        assertThrows(BadRequestException.class, () -> service.benchmarkAdmission(ITEM, USER, "nope"));

        verify(metrics, never()).enteredOrderProcessor();
    }

    @Test
    void issuingATokenIsDelegatedToTheEligibilityService() {
        when(eligibility.issue(ITEM, USER)).thenReturn("token-abc");

        assertEquals("token-abc", service.issueToken(ITEM, USER));
    }
}
