package dev.yuemeng.marthub.flashsale;

import dev.yuemeng.marthub.auth.SessionUser;
import dev.yuemeng.marthub.common.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private EligibilityService eligibility;
    private RedisRateLimiter limiter;
    private OrderService orders;
    private FlashSaleMetrics metrics;
    private FlashSaleService service;

    @BeforeEach
    void setUp() {
        eligibility = mock(EligibilityService.class);
        limiter = mock(RedisRateLimiter.class);
        orders = mock(OrderService.class);
        metrics = mock(FlashSaleMetrics.class);
        service = new FlashSaleService(eligibility, limiter, orders, metrics);
        // Both buckets have room by default; the tests that care about a limit override one.
        // Without this, an unstubbed allowUser returns false and every admission test would be
        // rejected by the per-user gate for the wrong reason.
        when(limiter.allowUser(anyLong(), anyLong())).thenReturn(true);
        when(limiter.allow(anyLong())).thenReturn(true);
    }

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
    void anInvalidTokenIsRejectedBeforeTheRateLimiterIsEvenConsulted() {
        when(eligibility.valid(anyLong(), anyLong(), anyString())).thenReturn(false);

        assertThrows(BadRequestException.class, () -> service.placeOrder(ITEM, USER, "nope"));

        verifyNoInteractions(limiter);
    }

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

    @Test
    void anAdmittedRequestCreatesTheOrderAndMarksTheUserAsHavingBought() {
        when(eligibility.valid(ITEM, USER.id(), "good-token")).thenReturn(true);
        when(limiter.allow(ITEM)).thenReturn(true);
        when(orders.create(ITEM, USER.id())).thenReturn(9001L);

        assertEquals(9001L, service.placeOrder(ITEM, USER, "good-token"));

        verify(orders).create(ITEM, USER.id());
        verify(eligibility).markBought(ITEM, USER.id());
    }

    @Test
    void aFailedOrderDoesNotMarkTheUserAsHavingBought() {
        when(eligibility.valid(ITEM, USER.id(), "good-token")).thenReturn(true);
        when(limiter.allow(ITEM)).thenReturn(true);
        when(orders.create(ITEM, USER.id())).thenThrow(new BadRequestException("SOLD_OUT", "sold out"));

        assertThrows(BadRequestException.class, () -> service.placeOrder(ITEM, USER, "good-token"));

        verify(eligibility, never()).markBought(anyLong(), anyLong());
    }

    @Test
    void theBenchmarkAdmissionPathRunsTheSameChecksWithoutTouchingStock() {
        when(eligibility.valid(ITEM, USER.id(), "good-token")).thenReturn(true);
        when(limiter.allow(ITEM)).thenReturn(true);

        service.benchmarkAdmission(ITEM, USER, "good-token");

        verify(metrics).enteredOrderProcessor();
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

    @Test
    void aUserOverTheirOwnLimitIsRejectedWithoutSpendingTheItemsTokens() {
        when(eligibility.valid(ITEM, USER.id(), "good-token")).thenReturn(true);
        when(limiter.allowUser(ITEM, USER.id())).thenReturn(false);

        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.placeOrder(ITEM, USER, "good-token"));

        assertEquals("RATE_LIMITED", e.code());
        // The shared bucket must not be touched. That ordering is the whole point: one caller
        // retrying a sold-out item keeps a valid token, so if a per-user rejection still spent
        // an item token it could drain the allowance everyone else is waiting on.
        verify(limiter, never()).allow(anyLong());
        verifyNoInteractions(orders);
        verify(metrics).rejectedBeforeOrder();
        verify(metrics, never()).enteredOrderProcessor();
    }
}
