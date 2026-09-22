package com.clinexa.care.accesslog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.clinexa.care.support.CareIntegrationTest;
import com.clinexa.care.support.CareTestData;
import com.clinexa.care.support.SessionsCare;
import com.clinexa.shared.security.fixtures.SecurityFixtures;
import com.clinexa.shared.security.fixtures.SecurityFixtures.AccountFixture;

/**
 * <strong>P4 — the minimal access log</strong> (guide, step 9).
 * <p>
 * It is not a comfort feature: it is the direct counterpart of {@code SEC-03}, the decision to share
 * a patient record between every practitioner of a clinic. Since compartmentalisation does not
 * exist, traceability replaces it — an access to health data has to be explainable afterwards.
 * <p>
 * Two properties matter and both are checked here: only <em>successful</em> clinical reads are
 * recorded, and a line, once written, cannot be changed or removed by anyone — the application
 * included.
 */
@CareIntegrationTest
class AccessLogTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	SessionsCare sessions;

	@Autowired
	CareTestData data;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void install() {
		this.data.install();
	}

	@Test
	void aClinicalReadIsLogged() throws Exception {
		long before = lines();

		read("clinical", SecurityFixtures.ALICE, SecurityFixtures.CLINIC_A, SecurityFixtures.P1_A.id());

		assertThat(lines()).isEqualTo(before + 1);
		Map<String, Object> line = lastLine();
		assertThat(line).containsEntry("clinic_id", SecurityFixtures.CLINIC_A)
			.containsEntry("account_id", SecurityFixtures.ALICE.id())
			.containsEntry("role", "PRACTITIONER")
			.containsEntry("action", "READ")
			.containsEntry("resource", "clinical_record")
			.containsEntry("resource_id", SecurityFixtures.P1_A.id());
		assertThat(line.get("occurred_at")).isNotNull();
	}

	// The administrative read is not clinical content: logging it would bury the accesses that
	// matter under the ones that do not.
	@Test
	void anAdministrativeReadIsNotLogged() throws Exception {
		long before = lines();
		read("administrative", SecurityFixtures.BOB, SecurityFixtures.CLINIC_A, SecurityFixtures.P1_A.id());
		assertThat(lines()).isEqualTo(before);
	}

	// An attempt that was refused, or a record that does not exist, is not an access to anything.
	// Recording them would fill the log with things that never happened.
	@Test
	void aRefusedOrNotFoundAccessIsNotLogged() throws Exception {
		long before = lines();

		// Refused by the role rule.
		read("clinical", SecurityFixtures.BOB, SecurityFixtures.CLINIC_A, SecurityFixtures.P1_A.id());
		// Refused by L1 — another clinic's path.
		read("clinical", SecurityFixtures.ALICE, SecurityFixtures.CLINIC_B, SecurityFixtures.P1_B.id());
		// Found nothing — another clinic's identifier on a legitimate path.
		read("clinical", SecurityFixtures.ALICE, SecurityFixtures.CLINIC_A, SecurityFixtures.P1_B.id());

		assertThat(lines()).isEqualTo(before);
	}

	/**
	 * Append-only, enforced by the database. The rule lives in a trigger rather than in a habit
	 * because a log the application can rewrite is evidence of nothing.
	 */
	@Test
	void theLogCannotBeAltered() throws Exception {
		read("clinical", SecurityFixtures.ALICE, SecurityFixtures.CLINIC_A, SecurityFixtures.P1_A.id());
		UUID line = (UUID) lastLine().get("id");

		assertThatThrownBy(() -> this.jdbc.update("UPDATE access_log SET action = 'NOTHING' WHERE id = ?", line))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> this.jdbc.update("DELETE FROM access_log WHERE id = ?", line))
			.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(this.jdbc.queryForObject("SELECT action FROM access_log WHERE id = ?", String.class, line))
			.isEqualTo("READ");
	}

	// It is a tenant table like any other: a clinic reads its own log and nobody else's.
	@Test
	void theLogIsPartitionedByClinic() throws Exception {
		read("clinical", SecurityFixtures.ALICE, SecurityFixtures.CLINIC_A, SecurityFixtures.P1_A.id());
		read("clinical", SecurityFixtures.DAN, SecurityFixtures.CLINIC_B, SecurityFixtures.P1_B.id());

		assertThat(this.jdbc.queryForObject(
				"SELECT count(*) FROM access_log WHERE clinic_id = ? AND resource_id = ?", Integer.class,
				SecurityFixtures.CLINIC_A, SecurityFixtures.P1_B.id()))
			.as("clinic B's record never appears in clinic A's log")
			.isZero();
	}

	private void read(String section, AccountFixture account, UUID clinicId, UUID recordId) throws Exception {
		this.mvc.perform(get("/api/v1/clinics/{c}/records/{d}/" + section, clinicId, recordId)
			.cookie(this.sessions.login(account)));
	}

	private long lines() {
		return this.jdbc.queryForObject("SELECT count(*) FROM access_log", Long.class);
	}

	private Map<String, Object> lastLine() {
		return this.jdbc.queryForMap("SELECT * FROM access_log ORDER BY occurred_at DESC LIMIT 1");
	}

}
