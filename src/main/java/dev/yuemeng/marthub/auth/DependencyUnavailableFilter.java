package dev.yuemeng.marthub.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.yuemeng.marthub.common.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * The 503 mapping that {@code @RestControllerAdvice} cannot reach.
 *
 * <p>Controller advice only sees exceptions raised once dispatch has begun. The session lookup
 * happens earlier than that -- inside the security filter chain -- so when Redis is unavailable
 * that failure never passes through the advice and came back as a 500. And since the session
 * layer moved onto Redis, that is the path *every* authenticated request takes, which made the
 * common failure the one reported as a server defect.
 *
 * <p>Verified rather than assumed: pausing Redis and calling a protected route returned 500
 * before this filter existed, while an unauthenticated route on the same instance still returned
 * 200 from the local cache.
 *
 * <p>Registered ahead of the security chain, so it wraps the filters whose exceptions it is here
 * to translate. The cause chain is walked because the originating exception may arrive wrapped.
 */
public class DependencyUnavailableFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;

    public DependencyUnavailableFilter(ObjectMapper mapper) { this.mapper = mapper; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException e) {
            if (!dependencyUnavailable(e) || response.isCommitted()) throw e;
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader(HttpHeaders.RETRY_AFTER, "1");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(),
                    new ApiError("DEPENDENCY_UNAVAILABLE", "cache unavailable, retry shortly"));
        }
    }

    /** Both shapes a Redis outage takes: refused outright, and accepted then too slow to answer. */
    private static boolean dependencyUnavailable(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof RedisConnectionFailureException || cause instanceof QueryTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
