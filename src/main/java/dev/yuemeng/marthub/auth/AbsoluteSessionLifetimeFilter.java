package dev.yuemeng.marthub.auth;

import dev.yuemeng.marthub.config.MartHubProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * The one session policy the framework does not bring: a hard ceiling on session age.
 *
 * <p>Spring Session gives an idle timeout -- {@code maxInactiveInterval} -- which expires a
 * session that goes quiet. That is not the same guarantee. Under an idle timeout alone a session
 * that keeps being used never expires, so a stolen token stays alive for as long as the thief
 * keeps making requests. An idle timeout protects abandoned sessions, not leaked ones.
 *
 * <p>So both clocks apply: idle timeout from Spring Session, absolute lifetime from here. The
 * creation time comes from the session itself rather than an attribute of our own, because
 * Spring Session already tracks it.
 */
@Component
public class AbsoluteSessionLifetimeFilter extends OncePerRequestFilter {
    private final Duration absoluteLifetime;

    public AbsoluteSessionLifetimeFilter(MartHubProperties props) {
        this.absoluteLifetime = props.getAuth().getAbsoluteLifetime();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null
                && System.currentTimeMillis() - session.getCreationTime() > absoluteLifetime.toMillis()) {
            session.invalidate();
            response.sendError(401, "session expired, re-authentication required");
            return;
        }
        chain.doFilter(request, response);
    }
}
