package dev.yuemeng.marthub.support;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Shared setup for the tests that need a real Redis.
 *
 * The Lua scripts in this package are the whole point of the flash-sale design, and a
 * mocked {@code StringRedisTemplate} cannot say anything about whether they are atomic.
 * So these tests talk to an actual server: CI supplies one as a service container, and
 * locally you can start one with
 *
 * <pre>docker run --rm -p 6379:6379 redis:7.4-alpine</pre>
 *
 * Point them elsewhere with {@code MARTHUB_TEST_REDIS=host:port}. When no server is
 * reachable the tests skip rather than fail, so a plain {@code mvn test} on a machine
 * without Redis still passes — the skip is visible in the surefire output.
 */
public final class RedisTestSupport {

    private RedisTestSupport() {}

    public static String host() {
        return endpoint()[0];
    }

    public static int port() {
        return Integer.parseInt(endpoint()[1]);
    }

    public static boolean redisAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host(), port()), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static StringRedisTemplate connect() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration(host(), port()));
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        return redis;
    }

    private static String[] endpoint() {
        String configured = System.getenv("MARTHUB_TEST_REDIS");
        if (configured == null || configured.isBlank()) {
            return new String[] {"localhost", "6379"};
        }
        int colon = configured.lastIndexOf(':');
        return colon < 0
                ? new String[] {configured, "6379"}
                : new String[] {configured.substring(0, colon), configured.substring(colon + 1)};
    }
}
