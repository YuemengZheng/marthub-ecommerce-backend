package dev.yuemeng.marthub.config;

import dev.yuemeng.marthub.auth.LoginInterceptor;
import dev.yuemeng.marthub.auth.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final RefreshTokenInterceptor refresh; private final LoginInterceptor login;
    public WebConfig(RefreshTokenInterceptor refresh, LoginInterceptor login){this.refresh=refresh;this.login=login;}
    @Override public void addInterceptors(InterceptorRegistry registry){
        registry.addInterceptor(refresh).addPathPatterns("/**").order(0);
        registry.addInterceptor(login)
                .addPathPatterns("/api/shops/**","/api/flash-sale/**")
                .excludePathPatterns("/api/auth/**","/actuator/**","/internal/benchmark/**")
                .order(1);
    }
}
