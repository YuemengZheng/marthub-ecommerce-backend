package dev.yuemeng.marthub.auth;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth){this.auth=auth;}
    @PostMapping("/demo-login") public Map<String,String> login(@RequestParam(defaultValue="1") long userId,
                                                               @RequestParam(defaultValue="Demo User") String name){
        return Map.of("token",auth.login(userId,name));
    }
}
