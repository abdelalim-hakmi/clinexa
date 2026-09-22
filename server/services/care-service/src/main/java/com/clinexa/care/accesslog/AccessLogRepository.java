package com.clinexa.care.accesslog;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Writes to the access log, and reads of it.
 * <p>
 * {@code JpaRepository} brings {@code delete*} methods that must never be called here: the table
 * is append-only, and the database trigger of migration {@code 002} refuses the statement anyway.
 * Purge and retention belong to Hardening, and will be a decision with its own migration — not a
 * repository call someone reaches for.
 */
public interface AccessLogRepository extends JpaRepository<AccessLog, UUID> {

	long countByResourceId(UUID resourceId);

}
