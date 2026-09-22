package com.clinexa.care.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.clinexa.care.record.clinical.ClinicalRecordRepository;
import com.clinexa.care.support.CareIntegrationTest;
import com.clinexa.care.support.CareTestData;
import com.clinexa.care.support.SessionsCare;
import com.clinexa.shared.security.fixtures.SecurityFixtures;
import com.clinexa.shared.security.fixtures.SecurityFixtures.AccountFixture;
import com.clinexa.shared.security.tenant.SecurityDenial;
import com.clinexa.shared.security.tenant.TenantContext;

import jakarta.servlet.http.Cookie;

/**
 * <strong>F3 — Tenant isolation</strong>: criteria 4, 5 (both halves, separately), 6 and 10.
 * <p>
 * Every assertion here is only worth something because clinic B's data <em>exists</em>: P1·B is the
 * same person as P1·A — same phone, same date of birth — with nothing linking the two rows. A test
 * that passed because the other clinic were empty would be vacuous, and silently so.
 */
@CareIntegrationTest
class TenantIsolationTest {

	private static final String ADMINISTRATIVE = "/api/v1/clinics/{c}/records/{d}/administrative";

	private static final String CLINICAL = "/api/v1/clinics/{c}/records/{d}/clinical";

	@Autowired
	MockMvc mvc;

	@Autowired
	SessionsCare sessions;

	@Autowired
	CareTestData data;

	@Autowired
	PatientRecordRepository records;

	@Autowired
	ClinicalRecordRepository clinicalRecords;

	@Autowired
	TenantContext tenantContext;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void install() {
		this.data.install();
	}

	@AfterEach
	void clear() {
		this.tenantContext.clear();
	}

	// The data of the other clinic is really there. Everything below depends on this.
	@Test
	void clinicBsDataReallyExists() {
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM patient_record WHERE clinic_id = ?", Integer.class,
				SecurityFixtures.CLINIC_B)).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM clinical_record WHERE clinic_id = ?",
				Integer.class, SecurityFixtures.CLINIC_B)).isEqualTo(1);
	}

	// The legitimate read, so the refusals below are not passing for an unrelated reason.
	@Test
	void aliceCanReadHerOwnClinicsRecord() throws Exception {
		MvcResult result = read(CLINICAL, SecurityFixtures.ALICE, SecurityFixtures.CLINIC_A,
				SecurityFixtures.P1_A.id());

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getContentAsString()).contains(SecurityFixtures.P1_A.medicalHistory());
	}

	/**
	 * <strong>Criterion 5a</strong> — {@code alice} of clinic A on a path of clinic B: {@code 403},
	 * refused by L1 because B is not among her memberships. Saying so reveals nothing she does not
	 * already know: a clinic is a public entity.
	 */
	@Test
	void criterion5aAnotherClinicsPathIs403() throws Exception {
		for (String route : new String[] { ADMINISTRATIVE, CLINICAL }) {
			MvcResult result = read(route, SecurityFixtures.ALICE, SecurityFixtures.CLINIC_B,
					SecurityFixtures.P1_B.id());

			assertThat(result.getResponse().getStatus()).as(route).isEqualTo(403);
			assertThat(result.getResponse().getContentAsString()).contains("AUTH_CLINIC_NOT_ASSIGNED")
				.as("a refusal never contains data of the other clinic")
				.doesNotContain(SecurityFixtures.P1_B.medicalHistory());
		}
	}

	/**
	 * <strong>Criterion 5b</strong> — a legitimate path, an identifier belonging to clinic B:
	 * {@code 404}, because {@code @TenantId} finds nothing. Answering {@code 403} here would confirm
	 * that the identifier <em>exists</em> somewhere, which is exactly what two unlinked records of
	 * the same person must never reveal.
	 * <p>
	 * The two halves are asserted separately on purpose: an implementation returning {@code 403}
	 * both times passes a test that only checks 5a, while leaking existence in 5b.
	 */
	@Test
	void criterion5bAnotherClinicsIdentifierIs404() throws Exception {
		for (String route : new String[] { ADMINISTRATIVE, CLINICAL }) {
			MvcResult result = read(route, SecurityFixtures.ALICE, SecurityFixtures.CLINIC_A,
					SecurityFixtures.P1_B.id());

			assertThat(result.getResponse().getStatus()).as(route).isEqualTo(404);
			assertThat(result.getResponse().getContentAsString()).doesNotContain("Tazi")
				.doesNotContain(SecurityFixtures.P1_B.medicalHistory());
		}
	}

	// Criterion 4, entity by entity, at the layer below HTTP: the ORM itself must not return the
	// other clinic's row, whatever the caller asks for.
	@Test
	void criterion4NoEntityOfBIsReachableFromA() {
		this.tenantContext.set(SecurityFixtures.CLINIC_A);

		assertThat(this.records.findById(SecurityFixtures.P1_B.id())).isEmpty();
		assertThat(this.clinicalRecords.findById(SecurityFixtures.P1_B.id())).isEmpty();
		assertThat(this.records.findAll()).extracting(PatientRecord::getId)
			.containsExactlyInAnyOrder(SecurityFixtures.P1_A.id(), SecurityFixtures.P2_A.id());
		assertThat(this.clinicalRecords.findAll()).hasSize(2);

		this.tenantContext.set(SecurityFixtures.CLINIC_B);
		assertThat(this.records.findById(SecurityFixtures.P1_A.id())).isEmpty();
		assertThat(this.records.findAll()).extracting(PatientRecord::getId).containsExactly(SecurityFixtures.P1_B.id());
	}

	/**
	 * <strong>Criterion 10</strong> — a tenant operation with no context refuses, and above all
	 * never returns everything. The ORM answers on a tenant no clinic can have, so the result is
	 * empty rather than complete; the application guard turns that into a {@code 403}.
	 */
	@Test
	void criterion10NoContextNoDataAndARefusal() {
		assertThat(this.tenantContext.clinicId()).isEmpty();

		assertThat(this.records.findAll()).as("never 'no filter, so everything'").isEmpty();
		assertThat(this.records.findById(SecurityFixtures.P1_A.id())).isEmpty();
		assertThatThrownBy(() -> this.tenantContext.requireClinicId()).isInstanceOf(SecurityDenial.class);
	}

	/**
	 * Criterion 10, write side. A read with no context is empty; a <em>write</em> with none carries
	 * the sentinel tenant, and the database — not the application — has to refuse it. Care has no
	 * {@code clinic} table to point a foreign key at, so this is the CHECK constraint's job.
	 */
	@Test
	void criterion10AWriteWithNoContextIsRefusedByTheDatabase() {
		assertThat(this.tenantContext.clinicId()).isEmpty();

		assertThatThrownBy(() -> this.records.saveAndFlush(new PatientRecord("No", "Clinic")))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}

	/**
	 * L4 on the self-reference: a patient record cannot be "merged into" a record of another clinic.
	 * The composite foreign key refuses it, which is the same rule guide 3.5 applies between tables.
	 */
	@Test
	void mergingIntoAnotherClinicsRecordIsRefusedByTheDatabase() {
		assertThatThrownBy(() -> this.jdbc.update("UPDATE patient_record SET merged_into = ? WHERE id = ?",
				SecurityFixtures.P1_B.id(), SecurityFixtures.P1_A.id()))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

		// ... while a merge inside the same clinic is accepted, so the refusal above is about the
		// clinic and not about the column.
		assertThat(this.jdbc.update("UPDATE patient_record SET merged_into = ? WHERE id = ?",
				SecurityFixtures.P1_A.id(), SecurityFixtures.P2_A.id())).isEqualTo(1);
		this.jdbc.update("UPDATE patient_record SET merged_into = NULL WHERE id = ?", SecurityFixtures.P2_A.id());
	}

	/**
	 * <strong>Criterion 6</strong> — the decisive fixture. {@code erin} is PRACTITIONER in A and
	 * RECEPTIONIST in B, so she sees the clinical record of A and not the one of B. A model carrying
	 * the role on the account could not even express her.
	 */
	@Test
	void criterion6ErinSeesTheClinicalRecordOfAAndNotOfB() throws Exception {
		MvcResult inA = read(CLINICAL, SecurityFixtures.ERIN, SecurityFixtures.CLINIC_A, SecurityFixtures.P1_A.id());
		assertThat(inA.getResponse().getStatus()).isEqualTo(200);
		assertThat(inA.getResponse().getContentAsString()).contains(SecurityFixtures.P1_A.medicalHistory());

		MvcResult inB = read(CLINICAL, SecurityFixtures.ERIN, SecurityFixtures.CLINIC_B, SecurityFixtures.P1_B.id());
		assertThat(inB.getResponse().getStatus()).as("receptionist in B: no clinical access").isEqualTo(403);
		assertThat(inB.getResponse().getContentAsString()).doesNotContain(SecurityFixtures.P1_B.medicalHistory());

		// ... and her administrative access in B does work, which is what makes the refusal above a
		// role refusal rather than a tenant one.
		MvcResult administrativeInB = read(ADMINISTRATIVE, SecurityFixtures.ERIN, SecurityFixtures.CLINIC_B,
				SecurityFixtures.P1_B.id());
		assertThat(administrativeInB.getResponse().getStatus()).isEqualTo(200);
	}

	/**
	 * <strong>Criterion 11</strong>, negative test — no {@code /api/v1/me/**} route exists at the
	 * foundation, because there is no patient account and no patient session ({@code SEC-02}). The
	 * last line of the matrix refuses them.
	 */
	@Test
	void criterion11NoMeRouteIsExposed() throws Exception {
		for (String path : new String[] { "/api/v1/me", "/api/v1/me/records", "/api/v1/me/rendez-vous" }) {
			assertThat(this.mvc.perform(get(path).cookie(this.sessions.login(SecurityFixtures.ALICE)))
				.andReturn()
				.getResponse()
				.getStatus()).as(path).isIn(403, 404);
		}
	}

	private MvcResult read(String route, AccountFixture account, UUID clinicId, UUID recordId) throws Exception {
		Cookie session = this.sessions.login(account);
		return this.mvc.perform(get(route, clinicId, recordId).cookie(session)).andReturn();
	}

}
