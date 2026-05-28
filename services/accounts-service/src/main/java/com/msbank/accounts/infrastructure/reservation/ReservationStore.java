package com.msbank.accounts.infrastructure.reservation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Side table tracking outstanding reservations. Used for fast idempotency lookup
 * by reservationId without replaying the aggregate.
 */
@Component
public class ReservationStore {

    public enum Status { HELD, COMMITTED, RELEASED }

    public record Row(UUID reservationId, UUID accountId, UUID transactionId,
                      long amount, String currency, Status status) {}

    private final JdbcTemplate jdbc;

    public ReservationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Row> find(UUID reservationId) {
        return jdbc.query("""
                SELECT reservation_id, account_id, transaction_id, amount, currency, status
                FROM reservations WHERE reservation_id = ?
                """, rs -> rs.next() ? Optional.of(new Row(
                        (UUID) rs.getObject("reservation_id"),
                        (UUID) rs.getObject("account_id"),
                        (UUID) rs.getObject("transaction_id"),
                        rs.getLong("amount"),
                        rs.getString("currency"),
                        Status.valueOf(rs.getString("status")))) : Optional.empty(), reservationId);
    }

    public void insertHeld(UUID reservationId, UUID accountId, UUID transactionId, long amount, String currency) {
        jdbc.update("""
                INSERT INTO reservations (reservation_id, account_id, transaction_id, amount, currency, status)
                VALUES (?, ?, ?, ?, ?, 'HELD')
                ON CONFLICT (reservation_id) DO NOTHING
                """, reservationId, accountId, transactionId, amount, currency);
    }

    public void updateStatus(UUID reservationId, Status status) {
        jdbc.update("UPDATE reservations SET status = ?, updated_at = now() WHERE reservation_id = ?",
                status.name(), reservationId);
    }
}
