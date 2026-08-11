package dev.yuemeng.marthub.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {
    private final AuthService auth;
    public RefreshTokenInterceptor(AuthService auth){this.auth=auth;}
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler){
        String header=request.getHeader("Authorization");
        String token=header!=null && header.startsWith("Bearer ") ? header.substring(7) : null;
        SessionUser user=auth.resolveAndRefresh(token); if(user!=null) UserContext.set(user); return true;
    }
    @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex){UserContext.clear();}
}
