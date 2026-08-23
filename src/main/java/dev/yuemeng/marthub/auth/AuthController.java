package dev.yuemeng.marthub.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Sessions, not credentials.
 *
 * <p>{@code demo-login} takes a user id and issues a session. There is no password, no credential
 * store, no verification of any kind -- it is a stub that stands in for authentication so the
 * parts this project is actually about can be exercised: session state shared across instances,
 * default-deny authorization, bounded lifetime, and revocation. Calling this an authentication
 * system would be wrong; it is session and authorization infrastructure with the credential step
 * left as a seam.
 *
 * <p>The session id comes back in the {@code X-Auth-Token} response header, which is Spring
 * Session's own mechanism under {@code HeaderHttpSessionIdResolver}, not something assembled here.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final SecurityContextRepository securityContextRepository;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public AuthController(SecurityContextRepository securityContextRepository,
                          FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.securityContextRepository = securityContextRepository;
        this.sessions = sessions;
    }

    @PostMapping("/demo-login")
    public Map<String, Object> login(@RequestParam(defaultValue = "1") long userId,
                                     @RequestParam(defaultValue = "Demo User") String name,
                                     HttpServletRequest request, HttpServletResponse response) {
        SessionUser user = new SessionUser(userId, name);
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        // Saving the context is what creates the session and persists it to Redis; Spring Session
        // then writes the principal index off the back of it.
        securityContextRepository.saveContext(context, request, response);
        return Map.of("userId", userId, "name", name);
    }

    /** Ends this one session. The whole point of server-side sessions: revocation is a delete. */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        return Map.of("loggedOut", true);
    }

    /**
     * Ends every session this user has, on every instance -- what a password change or a
     * compromised account needs. This is the capability a self-contained token cannot give
     * without bolting a revocation list back on, and it is the reason to keep session state
     * server-side.
     */
    @PostMapping("/revoke-all")
    public Map<String, Object> revokeAll(Authentication authentication) {
        Map<String, ? extends Session> found = sessions.findByPrincipalName(authentication.getName());
        found.keySet().forEach(sessions::deleteById);
        SecurityContextHolder.clearContext();
        return Map.of("revoked", found.size());
    }

    /** Who the current session belongs to. Also the smallest possible probe of default-deny. */
    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        SessionUser user = (SessionUser) authentication.getPrincipal();
        return Map.of("id", user.id(), "name", user.name());
    }
}
