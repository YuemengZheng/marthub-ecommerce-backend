package dev.yuemeng.marthub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisIndexedHttpSession;
import org.springframework.session.web.http.HeaderHttpSessionIdResolver;
import org.springframework.session.web.http.HttpSessionIdResolver;

import dev.yuemeng.marthub.auth.AbsoluteSessionLifetimeFilter;

/**
 * Session state lives in Redis so the app instances stay disposable: a session created on one
 * instance is usable on the others, which is what removes the sticky-session requirement and
 * lets a rolling restart happen without signing anyone out.
 *
 * <p>Three decisions worth naming.
 *
 * <p><b>Indexing is opt-in.</b> {@code @EnableRedisIndexedHttpSession} selects
 * {@code RedisIndexedSessionRepository} rather than the plain one, and only that repository
 * implements {@code FindByIndexNameSessionRepository} -- which is what makes "sign this user out
 * everywhere" possible. Spring Session derives the principal index from the security context, so
 * {@link dev.yuemeng.marthub.auth.SessionUser} implements {@code Principal} to keep that index
 * key a bare user id.
 *
 * <p><b>The session id travels in a header, not a cookie.</b> This is a non-browser API -- the
 * clients are scripts -- so {@code X-Auth-Token} is the honest transport. It is deliberately not
 * called {@code Authorization: Bearer}: that spelling implies OAuth token semantics this does not
 * have.
 *
 * <p><b>CSRF is disabled, and the reason is specific.</b> Not "header auth cannot be attacked" --
 * rather, the credential here is supplied explicitly in a custom header instead of being attached
 * automatically by a browser, which is the precondition CSRF depends on. Move the session id back
 * into a cookie and CSRF has to come back on.
 *
 * <p>Authorization is default-deny: {@code anyRequest().authenticated()}. A route added later is
 * protected unless someone deliberately opens it, so forgetting the config fails as a 401 rather
 * than as a silently public endpoint.
 */
@Configuration
@EnableWebSecurity
@EnableRedisIndexedHttpSession
public class SecurityConfig {

    @Bean
    HttpSessionIdResolver httpSessionIdResolver() {
        return HeaderHttpSessionIdResolver.xAuthToken();
    }

    /**
     * Spring Security keeps this internal to the filter chain by default. It is a bean here
     * because the login endpoint establishes the authentication itself and has to persist the
     * context to the session -- the session being what Spring Session then stores in Redis and
     * indexes by principal.
     */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AbsoluteSessionLifetimeFilter absoluteLifetime,
                                    SecurityContextRepository securityContextRepository)
            throws Exception {
        http
                .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                // Without this, an unauthenticated request gets whatever the default entry point
                // does -- for a browser-shaped setup that is a redirect. An API wants a bare 401.
                .exceptionHandling(e -> e.authenticationEntryPoint(
                        (request, response, ex) -> response.sendError(401, "authentication required")))
                .addFilterAfter(absoluteLifetime, SecurityContextHolderFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/demo-login").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // Benchmark routes are already gated by marthub.benchmark.enabled, which
                        // is false outside the benchmark compose profile: with it off the handlers
                        // do not exist at all.
                        .requestMatchers("/internal/benchmark/**").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }
}
