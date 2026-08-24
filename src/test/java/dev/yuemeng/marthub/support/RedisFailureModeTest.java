package dev.yuemeng.marthub.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which exception each Redis failure mode actually produces.
 *
 * <p>This started as a print-only probe because the mapping in ApiExceptionHandler is only correct
 * if it names the real types, and reading the class hierarchy had already produced one wrong guess:
 * a command timeout does not always surface as a timeout. It depends on whether the connection was
 * established yet, and the two halves land on different exceptions.
 *
 * <p>Now it asserts, so the split is pinned. If a Lettuce or Spring Data upgrade re-routes one of
 * these, the handler stops covering it silently -- and this test is what notices.
 */
class RedisFailureModeTest {

    private static final Duration TIMEOUT = Duration.ofMillis(200);

    private static StringRedisTemplate templateFor(int port) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", port),
                LettuceClientConfiguration.builder().commandTimeout(TIMEOUT).build());
        factory.afterPropertiesSet();
        return new StringRedisTemplate(factory);
    }

    @Test
    void nothingListeningIsAConnectionFailure() throws IOException {
        int deadPort;
        try (ServerSocket probe = new ServerSocket(0)) { deadPort = probe.getLocalPort(); }

        assertThrows(RedisConnectionFailureException.class,
                () -> templateFor(deadPort).opsForValue().get("k"));
    }

    @Test
    void aHandshakeThatNeverCompletesIsAlsoAConnectionFailure() throws IOException {
        // Accepts the TCP connection and then answers nothing, so the timeout happens while the
        // connection is still being set up. Spring reports that as a connection failure even
        // though the underlying cause is a command timeout.
        try (ServerSocket blackHole = new ServerSocket(0)) {
            Thread accepter = new Thread(() -> {
                try { while (true) { Socket ignored = blackHole.accept(); } }
                catch (IOException stopped) { }
            });
            accepter.setDaemon(true);
            accepter.start();

            assertThrows(RedisConnectionFailureException.class,
                    () -> templateFor(blackHole.getLocalPort()).opsForValue().get("k"));
        }
    }

    @EnabledIf("dev.yuemeng.marthub.support.RedisTestSupport#redisAvailable")
    @Test
    void aStallOnAnEstablishedConnectionIsAQueryTimeout() throws IOException, InterruptedException {
        StringRedisTemplate live = templateFor(RedisTestSupport.port());
        live.opsForValue().get("warmup");        // connection established and verified healthy
        stallServer(RedisTestSupport.port());

        // The production shape: the pool is fine, Redis just stops answering. This is a different
        // exception from the two above, which is exactly why the handler needs both mappings.
        assertThrows(QueryTimeoutException.class, () -> live.opsForValue().get("k"));
    }

    /**
     * CLIENT PAUSE rather than DEBUG SLEEP: DEBUG is disabled by default on Redis 7.4, and a
     * rejected DEBUG looks exactly like a healthy server -- which is how the first version of this
     * probe reported "no exception" and nearly sent us to the wrong conclusion.
     */
    private static void stallServer(int port) throws IOException, InterruptedException {
        try (Socket raw = new Socket("localhost", port)) {
            raw.getOutputStream().write(
                    "*4\r\n$6\r\nCLIENT\r\n$5\r\nPAUSE\r\n$3\r\n800\r\n$3\r\nALL\r\n".getBytes());
            raw.getOutputStream().flush();
            raw.getInputStream().read();        // wait for +OK so the pause is definitely in effect
        }
        Thread.sleep(20);
    }
}
