package com.msbank.accounts.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read-side service. Queries the {@code account_view} projection table.
 * Never touches the event store (apart from the audit/events endpoint which lives in the command service).
 */
@Service
public class AccountQueryService {

    public record AccountView(UUID id, UUID customerId, String accountType, String status,
                              long balance, long availableBalance, String currency, String nickname,
                              long version, Instant createdAt, Instant updatedAt) {}

    private final JdbcTemplate jdbc;

    public AccountQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AccountView> findById(UUID id) {
        return jdbc.query("""
                SELECT id, customer_id, account_type, status, balance, available_balance,
                       currency, nickname, version, created_at, updated_at
                FROM account_view WHERE id = ?
                """, rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(), id);
    }

    public List<AccountView> listForCustomer(UUID customerId, String statusFilter) {
        if (statusFilter == null || statusFilter.isBlank()) {
            return jdbc.query("""
                    SELECT id, customer_id, account_type, status, balance, available_balance,
                           currency, nickname, version, created_at, updated_at
                    FROM account_view WHERE customer_id = ? ORDER BY created_at
                    """, (rs, n) -> map(rs), customerId);
        }
        return jdbc.query("""
                SELECT id, customer_id, account_type, status, balance, available_balance,
                       currency, nickname, version, created_at, updated_at
                FROM account_view WHERE customer_id = ? AND status = ? ORDER BY created_at
                """, (rs, n) -> map(rs), customerId, statusFilter);
    }

    private static AccountView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AccountView(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("customer_id"),
                rs.getString("account_type"),
                rs.getString("status"),
                rs.getLong("balance"),
                rs.getLong("available_balance"),
                rs.getString("currency"),
                rs.getString("nickname"),
                rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
