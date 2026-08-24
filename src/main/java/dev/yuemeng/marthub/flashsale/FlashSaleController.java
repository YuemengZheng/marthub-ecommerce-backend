package dev.yuemeng.marthub.flashsale;

import dev.yuemeng.marthub.auth.SessionUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * The caller arrives as an {@code @AuthenticationPrincipal} rather than out of a ThreadLocal the
 * way it used to. Same identity, but it is now a method parameter -- so it survives being handed
 * to another thread, and these routes cannot be reached unauthenticated because the filter chain
 * denies by default rather than because this class remembered to check.
 */
@RestController
@RequestMapping("/api/flash-sale")
public class FlashSaleController {
    private final FlashSaleService service;
    public FlashSaleController(FlashSaleService service){this.service=service;}

    @PostMapping("/{itemId}/eligibility")
    public Map<String,String> eligibility(@PathVariable long itemId,
                                          @AuthenticationPrincipal SessionUser user){
        return Map.of("token", service.issueToken(itemId, user));
    }

    /**
     * 200 whether the order was created now or created by an earlier request that this one repeats.
     * {@code replayed} is how the client tells them apart; it is not an error either way, because a
     * caller whose response was lost on the network still has an order and should be told which one.
     */
    @PostMapping("/{itemId}/orders")
    public Map<String,Object> order(@PathVariable long itemId,
                                    @RequestHeader("X-Eligibility-Token") String token,
                                    @AuthenticationPrincipal SessionUser user){
        OrderResult result = service.placeOrder(itemId, user, token);
        return Map.of("orderId", result.orderId(), "replayed", result.replayed());
    }
}
