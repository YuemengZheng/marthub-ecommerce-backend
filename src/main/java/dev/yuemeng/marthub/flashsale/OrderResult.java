package dev.yuemeng.marthub.flashsale;

/**
 * @param replayed true when this order already existed and the request is a repeat of one that
 *                 succeeded. The caller gets their real order id either way -- a client whose
 *                 response was lost on the network should see their order, not an error.
 */
public record OrderResult(long orderId, boolean replayed) {}
