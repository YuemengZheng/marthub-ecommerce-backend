package dev.yuemeng.marthub.auth;

import dev.yuemeng.marthub.support.RedisTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a caller sees when the session store is unreachable.
 *
 * <p>This was the gap: the mapping lived in {@code @RestControllerAdvice}, which only sees
 * exceptions raised after dispatch begins, and the session lookup happens earlier than that --
 * inside the security filter chain. So the most common Redis failure came back as a 500 and read
 * like a code defect. Pausing a real Redis and calling a protected route is what showed it;
 * inferring the status from the handler would have said 503.
 */
@EnabledIf("dev.yuemeng.marthub.support.RedisTestSupport#redisAvailable")
class DependencyFailureContractTest {

    private static ConfigurableApplicationContext app;
    private static String url;
    private static String token;

    @BeforeAll
    static void boot() {
        app = new SpringApplicationBuilder(SessionIntegrationTest.SessionOnlyApp.class).run(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--spring.data.redis.host=" + RedisTestSupport.host(),
                "--spring.data.redis.port=" + RedisTestSupport.port(),
                "--spring.data.redis.timeout=200ms",
                "--spring.session.timeout=30m",
                "--marthub.auth.absolute-lifetime=12h");
        url = "http://localhost:" + app.getEnvironment().getProperty("local.server.port");
        ResponseEntity<String> login = RestClient.create().post()
                .uri(url + "/api/auth/demo-login?userId=42&name=FailureProbe")
                .retrieve().toEntity(String.class);
        token = login.getHeaders().getFirst("X-Auth-Token");
        assertNotNull(token);
    }

    @AfterAll
    static void shutdown() {
        if (app != null && app.isActive()) app.close();
    }

    @Test
    void aSessionLookupThatCannotReachRedisIsServiceUnavailableNotAServerError() throws Exception {
        assertEquals(200, status("/api/auth/me"), "healthy to begin with");

        RedisTestSupport.stallServer(800);
        ResponseEntity<String> res;
        try {
            res = attempt("/api/auth/me");
        } finally {
            // The pause is server-wide and outlives this test; leaving it on stopped the next
            // test class from starting its context at all.
            RedisTestSupport.resumeServer();
        }

        assertEquals(503, res.getStatusCode().value(),
                "a dependency being down is not the caller's request being wrong");
        assertEquals("1", res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertTrue(res.getBody() != null && res.getBody().contains("DEPENDENCY_UNAVAILABLE"),
                "body should name the dependency failure, was: " + res.getBody());
    }

    private static int status(String path) {
        return attempt(path).getStatusCode().value();
    }

    private static ResponseEntity<String> attempt(String path) {
        try {
            return RestClient.create().get().uri(url + path)
                    .header("X-Auth-Token", token).retrieve().toEntity(String.class);
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .headers(e.getResponseHeaders())
                    .body(e.getResponseBodyAsString());
        }
    }
}
