package com.clinexa.identity.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.clinexa.shared.security.fixtures.SecurityFixtures;
import com.clinexa.shared.security.fixtures.SecurityFixtures.AccountFixture;
import com.clinexa.shared.security.identity.ClinicRole;

/**
 * Inserts {@link SecurityFixtures} into the {@code identity} database.
 * <p>
 * It writes through JDBC rather than through the service: there is no write route at the foundation
 * ({@code SEC-14}), and a fixture that had to go through the API would make the tests depend on the
 * very authorization rules they are meant to check.
 * <p>
 * <strong>Clinic B's rows are not optional.</strong> A cross-tenant test that passes because the
 * other clinic's data does not exist proves nothing, and nobody notices (04 §6). So both clinics
 * and all five accounts are always inserted, including {@code erin}, who belongs to both.
 * <p>
 * Idempotent: the containers are shared by every test class, so this runs many times against the
 * same database.
 */
@Component
public class TestData {

	private final JdbcTemplate jdbc;

	public TestData(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Restores the reference data set — exactly, every time.
	 * <p>
	 * The containers are shared by the whole build, so one test revoking {@code erin} in clinic B
	 * would silently change what the next class sees. "Insert if missing" is not enough for that:
	 * members are <strong>reset</strong> to {@code ACTIVE}, and any member a test created is removed.
	 * A suite whose result depends on the order its classes happen to run in is a suite nobody can
	 * trust when it turns red.
	 */
	@Transactional
	public void install() {
		clinic(SecurityFixtures.CLINIC_A, "Clinic A", "ICE-CABINET-A");
		clinic(SecurityFixtures.CLINIC_B, "Clinic B", "ICE-CABINET-B");

		List<UUID> expected = new ArrayList<>();
		for (AccountFixture account : SecurityFixtures.accounts()) {
			for (Map.Entry<UUID, Set<ClinicRole>> assignment : account.assignments().entrySet()) {
				assignment.getValue().forEach(role -> expected.add(account.memberId(assignment.getKey(), role)));
			}
		}
		// Before re-inserting, not after: a member a test created could otherwise collide with the
		// one being reactivated on the partial unique index over active roles.
		removeMembersAddedByATest(expected);

		for (AccountFixture account : SecurityFixtures.accounts()) {
			account(account);
			for (Map.Entry<UUID, Set<ClinicRole>> assignment : account.assignments().entrySet()) {
				for (ClinicRole role : assignment.getValue()) {
					member(account, assignment.getKey(), role);
				}
			}
		}
		this.jdbc.update("DELETE FROM outbound_event");
	}

	/** Removes anything a previous test added on a fixture account — a role change, for instance. */
	private void removeMembersAddedByATest(List<UUID> expected) {
		List<Object> params = new ArrayList<>();
		SecurityFixtures.accounts().forEach(account -> params.add(account.id()));
		String accounts = placeholders(SecurityFixtures.accounts().size());
		params.addAll(expected);
		this.jdbc.update("DELETE FROM member WHERE account_id IN (" + accounts + ") AND id NOT IN ("
				+ placeholders(expected.size()) + ")", params.toArray());
	}

	private static String placeholders(int count) {
		return String.join(",", java.util.Collections.nCopies(count, "?"));
	}

	private void clinic(UUID id, String legalName, String ice) {
		this.jdbc.update("""
				INSERT INTO clinic (id, legal_name, ice) VALUES (?, ?, ?)
				ON CONFLICT (id) DO NOTHING
				""", id, legalName, ice);
	}

	private void account(AccountFixture account) {
		this.jdbc.update("""
				INSERT INTO account (id, email, password_hash, last_name, first_name)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT (id) DO NOTHING
				""", account.id(), account.email(), SecurityFixtures.PASSWORD_HASH, account.lastName(),
				account.firstName());
	}

	private void member(AccountFixture account, UUID clinicId, ClinicRole role) {
		this.jdbc.update("""
				INSERT INTO member (id, account_id, clinic_id, role, start_date, status, created_by)
				VALUES (?, ?, ?, ?, DATE '2026-01-01', 'ACTIVE', ?)
				ON CONFLICT (id) DO UPDATE SET status = 'ACTIVE', end_date = NULL
				""", account.memberId(clinicId, role), account.id(), clinicId, role.name(), account.id());
	}

}
