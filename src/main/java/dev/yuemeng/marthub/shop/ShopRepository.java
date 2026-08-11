package dev.yuemeng.marthub.shop;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ShopRepository {
    private final JdbcTemplate jdbc;
    public ShopRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<Shop> findById(long id) {
        List<Shop> rows = jdbc.query("SELECT id,name,category,price_cents FROM shops WHERE id=?",
                (rs,n) -> new Shop(rs.getLong("id"), rs.getString("name"), rs.getString("category"), rs.getInt("price_cents")), id);
        return rows.stream().findFirst();
    }
    public void update(Shop shop) {
        jdbc.update("UPDATE shops SET name=?, category=?, price_cents=? WHERE id=?", shop.name(), shop.category(), shop.priceCents(), shop.id());
    }
}
