package dev.yuemeng.marthub.support;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A real MySQL for the tests that cannot be written against a mock.
 *
 * <p>Overselling is a race between two statements, so a mocked {@code JdbcTemplate} can say
 * nothing about it: the thing under test is what InnoDB does when two transactions reach the same
 * row, and only InnoDB can answer that. Same reasoning as {@link RedisTestSupport} for Lua.
 *
 * <p>Point it somewhere with {@code MARTHUB_TEST_MYSQL=host:port}. Defaults to a throwaway
 * instance on <b>3399</b> rather than 3306, because a default port is how a test suite ends up
 * dropping tables in whatever database happened to be running.
 */
public final class MySqlTestSupport {

    private MySqlTestSupport() {}

    private static String[] endpoint() {
        String configured = System.getenv("MARTHUB_TEST_MYSQL");
        if (configured == null || configured.isBlank()) return new String[]{"localhost", "3399"};
        String[] parts = configured.split(":");
        return new String[]{parts[0], parts.length > 1 ? parts[1] : "3399"};
    }

    public static boolean mysqlAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(endpoint()[0], Integer.parseInt(endpoint()[1])), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @param poolSize how many connections may reach the database at once. This is the knob that
     *                 decides how much real contention the test produces: with one connection the
     *                 race under test cannot happen at all.
     */
    public static DataSource dataSource(int poolSize) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:mysql://" + endpoint()[0] + ":" + endpoint()[1]
                + "/marthub?useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true");
        ds.setUsername("root");
        ds.setPassword("root");
        ds.setMaximumPoolSize(poolSize);
        // Matches the application: a bounded wait is what lets the processing lease have a bounded
        // TTL, and it also stops a stuck test from hanging for InnoDB's 50s default.
        ds.setConnectionInitSql("SET SESSION innodb_lock_wait_timeout = 5");
        return ds;
    }

    /**
     * Creates the tables from the application's own {@code schema.sql} rather than from a copy.
     * A hand-written copy of {@code uq_user_item} in a test file would keep passing after someone
     * changed the real constraint, which is the opposite of what this test is for.
     */
    public static void applySchema(JdbcTemplate jdbc) {
        String ddl;
        try (InputStream in = MySqlTestSupport.class.getResourceAsStream("/schema.sql")) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("could not read schema.sql", e);
        }
        for (String statement : ddl.split(";")) {
            if (!statement.isBlank()) jdbc.execute(statement);
        }
    }

    /** Leaves exactly one item with the given stock, and no orders. */
    public static void resetFixture(JdbcTemplate jdbc, long itemId, int stock) {
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM flash_sale_items");
        jdbc.update("DELETE FROM shops");
        jdbc.update("INSERT INTO shops(id,name,category,price_cents) VALUES (1,'t','t',1)");
        jdbc.update("INSERT INTO flash_sale_items(id,shop_id,stock,active) VALUES (?,1,?,TRUE)",
                itemId, stock);
    }
}
