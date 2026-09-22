package com.clinexa.care.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.clinexa.shared.security.fixtures.SecurityFixtures;
import com.clinexa.shared.security.fixtures.SecurityFixtures.RecordFixture;

/**
 * Inserts the patient records of {@link SecurityFixtures} into the {@code care} database.
 * <p>
 * Through JDBC, never through an API: the skeleton has no write route at all (SEC-14), and a
 * fixture that had to go through one would make these tests depend on the authorization rules they
 * exist to check.
 * <p>
 * <strong>P1·B is not optional.</strong> The cross-tenant assertions are only meaningful because
 * clinic B's record exists — same person, same phone, same date of birth as P1·A, and no link
 * between the two rows. A test that passed because the other clinic had no data would prove
 * nothing, and nobody would notice (04 §6).
 */
@Component
public class CareTestData {

	private final JdbcTemplate jdbc;

	public CareTestData(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Restores the patient records. It does <strong>not</strong> clear {@code access_log}, and it
	 * cannot: the trigger of migration {@code 002} refuses a {@code DELETE} from anyone, this test
	 * helper included. Tests that assert on the log therefore count the lines they add, from a
	 * baseline — which is what auditing an append-only table looks like in real life too.
	 */
	@Transactional
	public void install() {
		for (RecordFixture record : SecurityFixtures.records()) {
			this.jdbc.update("""
					INSERT INTO patient_record (id, clinic_id, last_name, first_name, date_of_birth, phone, coverage)
					VALUES (?, ?, ?, ?, ?, ?, ?)
					ON CONFLICT (id) DO NOTHING
					""", record.id(), record.clinicId(), record.lastName(), record.firstName(), record.dateOfBirth(),
					record.phone(), record.coverage());
			this.jdbc.update("""
					INSERT INTO clinical_record (record_id, clinic_id, medical_history,
					                              surgical_history, family_history)
					VALUES (?, ?, ?, ?, ?)
					ON CONFLICT (record_id) DO NOTHING
					""", record.id(), record.clinicId(), record.medicalHistory(), record.surgicalHistory(),
					record.familyHistory());
		}
	}

}
