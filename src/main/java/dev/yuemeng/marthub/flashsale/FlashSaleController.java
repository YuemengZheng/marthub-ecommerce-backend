package dev.yuemeng.marthub.flashsale;

import dev.yuemeng.marthub.auth.SessionUser;
import dev.yuemeng.marthub.auth.UserContext;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/flash-sale")
public class FlashSaleController {
    private final FlashSaleService service;
    public FlashSaleController(FlashSaleService service){this.service=service;}
    @PostMapping("/{itemId}/eligibility") public Map<String,String> eligibility(@PathVariable long itemId){
        SessionUser user=UserContext.get(); return Map.of("token",service.issueToken(itemId,user));
    }
    @PostMapping("/{itemId}/orders") public Map<String,Long> order(@PathVariable long itemId,@RequestHeader("X-Eligibility-Token") String token){
        return Map.of("orderId",service.placeOrder(itemId,UserContext.get(),token));
    }
}
