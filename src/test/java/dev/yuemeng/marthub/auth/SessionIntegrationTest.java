package dev.yuemeng.marthub.auth;

import dev.yuemeng.marthub.config.MartHubProperties;
import dev.yuemeng.marthub.config.SecurityConfig;
import dev.yuemeng.marthub.support.RedisTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the resume bullet claims, as tests.
 *
 * <p>Two application contexts run at once against one Redis. That is the whole point: it is the
 * only way to show the session belongs to Redis rather than to whichever JVM created it, which is
 * what removes the sticky-session requirement. A single-context test could not tell the two apart.
 *
 * <p>MySQL is deliberately absent. The datasource autoconfiguration is excluded and only the
 * security, session and auth beans are loaded, so these run wherever a Redis is reachable --
 * including CI, which supplies one and then fails the build if any test skipped.
 */
@EnabledIf("dev.yuemeng.marthub.support.RedisTestSupport#redisAvailable")
class SessionIntegrationTest {

    @SpringBootApplication
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class})
    @EnableConfigurationProperties(MartHubProperties.class)
    @Import({SecurityConfig.class, AuthController.class, AbsoluteSessionLifetimeFilter.class})
    public static class SessionOnlyApp {
        @RestController
        static class ProtectedProbe {
            /** A route nobody remembered to configure. Default-deny has to cover it anyway. */
            @GetMapping("/api/some/route/added/later")
            Map<String, String> later() { return Map.of("reached", "yes"); }

            /** Stands in for the catalogue: reading is public, writing is not. */
            @GetMapping("/api/shops/{id}")
            Map<String, Object> read(@PathVariable long id) { return Map.of("id", id); }

            @PutMapping("/api/shops/{id}")
            Map<String, Object> write(@PathVariable long id) { return Map.of("updated", id); }
        }
    }

    private static ConfigurableApplicationContext instanceA;
    private static ConfigurableApplicationContext instanceB;
    private static String urlA;
    private static String urlB;

    @BeforeAll
    static void bootTwoInstances() {
        instanceA = boot("A", "12h");
        instanceB = boot("B", "12h");
        urlA = "http://localhost:" + port(instanceA);
        urlB = "http://localhost:" + port(instanceB);
    }

    @AfterAll
    static void shutdown() {
        if (instanceA != null && instanceA.isActive()) instanceA.close();
        if (instanceB != null && instanceB.isActive()) instanceB.close();
    }

    private static ConfigurableApplicationContext boot(String id, String absoluteLifetime) {
        // Passed as command-line args, not builder properties: those land in Spring's default
        // property source, which sits *below* application.yml, so the yml's Redis port would win
        // and the app would dial 6379 instead of the test server.
        return new SpringApplicationBuilder(SessionOnlyApp.class).run(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--spring.data.redis.host=" + RedisTestSupport.host(),
                "--spring.data.redis.port=" + RedisTestSupport.port(),
                "--spring.session.timeout=30m",
                "--marthub.instance-id=app-" + id,
                "--marthub.auth.absolute-lifetime=" + absoluteLifetime);
    }

    private static int port(ConfigurableApplicationContext ctx) {
        return Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port", "0"));
    }

    private static String login(String baseUrl, long userId) {
        ResponseEntity<String> res = RestClient.create().post()
                .uri(baseUrl + "/api/auth/demo-login?userId=" + userId + "&name=Test" + userId)
                .retrieve().toEntity(String.class);
        String token = res.getHeaders().getFirst("X-Auth-Token");
        assertNotNull(token, "login must hand back a session id in X-Auth-Token");
        return token;
    }

    private static int get(String baseUrl, String path, String token) {
        try {
            RestClient.RequestHeadersSpec<?> spec = RestClient.create().get().uri(baseUrl + path);
            if (token != null) spec = spec.header("X-Auth-Token", token);
            return spec.retrieve().toBodilessEntity().getStatusCode().value();
        } catch (HttpStatusCodeException e) {
            return e.getStatusCode().value();
        }
    }

    private static int put(String baseUrl, String path, String token) {
        try {
            RestClient.RequestHeadersSpec<?> spec = RestClient.create().put().uri(baseUrl + path);
            if (token != null) spec = spec.header("X-Auth-Token", token);
            return spec.retrieve().toBodilessEntity().getStatusCode().value();
        } catch (HttpStatusCodeException e) {
            return e.getStatusCode().value();
        }
    }

    private static int post(String baseUrl, String path, String token) {
        try {
            RestClient.RequestHeadersSpec<?> spec = RestClient.create().post().uri(baseUrl + path);
            if (token != null) spec = spec.header("X-Auth-Token", token);
            return spec.retrieve().toBodilessEntity().getStatusCode().value();
        } catch (HttpStatusCodeException e) {
            return e.getStatusCode().value();
        }
    }

    @Test
    void aRouteNobodyConfiguredIsStillClosedWithoutASession() {
        // The old setup listed the paths to protect, so a route added later was public until
        // someone remembered it. anyRequest().authenticated() inverts that: forgetting now costs
        // a 401, which someone notices, instead of an open endpoint, which nobody does.
        assertEquals(401, get(urlA, "/api/some/route/added/later", null));
        assertEquals(401, get(urlA, "/api/auth/me", null));
    }

    @Test
    void readingTheCatalogueNeedsNoSessionButWritingToItDoes() {
        // Requiring a session to look at a product was inherited, not decided: the read path never
        // consults the caller. It also failed reads the local cache could have served, because the
        // session lookup runs in a filter ahead of the cache. Scoped to GET so the write stays shut.
        assertEquals(200, get(urlA, "/api/shops/7", null), "browsing is public");
        assertEquals(401, put(urlA, "/api/shops/7", null), "changing the catalogue is not");
    }

    @Test
    void aSessionFromOneInstanceWorksOnAnotherAndOutlivesTheOneThatMadeIt() {
        String token = login(urlA, 101);

        assertEquals(200, get(urlA, "/api/auth/me", token), "usable on the instance that made it");
        assertEquals(200, get(urlB, "/api/auth/me", token), "and on an instance that never saw the login");

        // Now take that instance away entirely -- a rolling restart, a scale-in, a crash. If the
        // session lived in instance A's heap this is where it would disappear.
        instanceA.close();
        assertEquals(200, get(urlB, "/api/auth/me", token), "the session belongs to Redis, not to A");

        instanceA = boot("A", "12h");
        urlA = "http://localhost:" + port(instanceA);
        assertEquals(200, get(urlA, "/api/auth/me", token), "and the replacement instance accepts it too");
    }

    @Test
    void anActivelyUsedSessionStillDiesAtTheAbsoluteCeiling() {
        // Spring Session's idle timeout only expires a session that goes quiet, so on its own a
        // stolen token survives as long as it keeps being used. This instance caps age at 1s.
        ConfigurableApplicationContext shortLived = boot("Short", "1s");
        try {
            String base = "http://localhost:" + port(shortLived);
            String token = login(base, 202);
            assertEquals(200, get(base, "/api/auth/me", token));

            Thread.sleep(1200);

            // Kept in use the whole time -- the idle clock never ran out. The absolute one did.
            assertEquals(401, get(base, "/api/auth/me", token),
                    "past the ceiling the session must be refused even though it stayed active");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        } finally {
            shortLived.close();
        }
    }

    @Test
    void revokingAUserEndsEveryOneOfTheirSessionsOnEveryInstance() {
        long userId = 303;
        String phone = login(urlA, userId);
        String laptop = login(urlA, userId);
        String tablet = login(urlB, userId);

        assertEquals(200, get(urlB, "/api/auth/me", phone));
        assertEquals(200, get(urlB, "/api/auth/me", laptop));
        assertEquals(200, get(urlA, "/api/auth/me", tablet));

        // Password changed, or the account was reported stolen. One call, three devices, and it
        // does not matter which instance issued them -- this is what a self-contained token
        // cannot do without a revocation list bolted back on.
        assertEquals(200, post(urlA, "/api/auth/revoke-all", phone));

        assertEquals(401, get(urlB, "/api/auth/me", phone));
        assertEquals(401, get(urlB, "/api/auth/me", laptop));
        assertEquals(401, get(urlA, "/api/auth/me", tablet));
    }
}
