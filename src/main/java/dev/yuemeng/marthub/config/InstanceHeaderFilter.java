package dev.yuemeng.marthub.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class InstanceHeaderFilter extends OncePerRequestFilter {
    private final MartHubProperties props;
    public InstanceHeaderFilter(MartHubProperties props) { this.props = props; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-MartHub-Instance", props.getInstanceId());
        chain.doFilter(request, response);
    }
}
