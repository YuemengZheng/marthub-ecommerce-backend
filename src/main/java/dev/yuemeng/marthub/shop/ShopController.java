package dev.yuemeng.marthub.shop;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/shops")
public class ShopController {
    private final ShopService service;
    public ShopController(ShopService service){this.service=service;}
    @GetMapping("/{id}") public Shop get(@PathVariable long id){ return service.get(id); }
    @PutMapping("/{id}") public void update(@PathVariable long id, @RequestBody Shop body){
        service.update(new Shop(id, body.name(), body.category(), body.priceCents()));
    }
}
