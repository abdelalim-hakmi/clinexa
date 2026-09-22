package com.clinexa.identity.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.clinexa.identity.support.TestData;
import com.clinexa.identity.support.Sessions;
import com.clinexa.identity.support.IdentityTestSupport;
import com.clinexa.shared.security.fixtures.SecurityFixtures;
import com.clinexa.shared.security.identity.ClinicRole;
import com.clinexa.shared.security.tenant.SecurityDenial;
import com.clinexa.shared.security.tenant.TenantContext;

import tools.jackson.databind.json.JsonMapper;

/**
 * The cross tests that pay for the {@code SEC-13} exemption.
 * <p>
 * {@link Member} is the one entity without {@code @TenantId}, so nothing in the ORM stops a query
 * from reaching another clinic's rows. The exemption was granted on three conditions — nominative,
 * whitelisted in INV-2, and <strong>covered by a cross test</strong>. This class is that third
 * condition, and it checks each of the three accesses the repository allows.
 * <p>
 * The precise claims, from guide 6.7: carol@A can neither list, nor read, nor revoke a member of
 * clinic B; and alice, who is not an administrator, does not see anyone's members at all.
 */
@IdentityTestSupport
class MemberSec13Test {

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	Sessions sessions;

	@Autowired
	TestData data;

	@Autowired
	MemberService members;

	@Autowired
	MemberRepository repository;

	@Autowired
	TenantContext tenantContext;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void install() {
		this.sessions = new Sessions(this.mvc, this.json);
		this.data.install();
	}

	@AfterEach
	void cleanUp() {
		this.tenantContext.clear();
	}

	// The legitimate case first, so the refusals below are not passing for the wrong reason.
	@Test
	void carolSeesTheTeamOfHerClinic() throws Exception {
		MvcResult result = list(SecurityFixtures.CLINIC_A);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		String body = result.getResponse().getContentAsString();
		assertThat(body).contains(SecurityFixtures.ALICE.id().toString())
			.contains(SecurityFixtures.BOB.id().toString())
			.contains(SecurityFixtures.ERIN.id().toString());
	}

	// Criterion 5a on this service: the clinic in the path is not one of carol's, so L1 refuses
	// with 403 — telling her so reveals nothing, clinic B is a public entity.
	@Test
	void carolDoesNotListTheTeamOfAnotherClinic() throws Exception {
		MvcResult result = list(SecurityFixtures.CLINIC_B);

		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		assertThat(result.getResponse().getContentAsString()).contains("AUTH_CLINIC_NOT_ASSIGNED")
			.as("a refusal never names the other clinic's data")
			.doesNotContain(SecurityFixtures.DAN.id().toString());
	}

	// Criterion 5b on this service: a legitimate path, but a member of clinic B. The manual filter
	// finds nothing, so 404 — a 403 here would confirm that the identifier exists somewhere.
	@Test
	void carolDoesNotReadAMemberOfTheOtherClinic() throws Exception {
		UUID memberOfB = SecurityFixtures.DAN.memberId(SecurityFixtures.CLINIC_B, ClinicRole.PRACTITIONER);
		assertThat(existsInDatabase(memberOfB)).as("B's data must exist, otherwise the test is meaningless").isTrue();

		MvcResult result = this.mvc
			.perform(get("/api/v1/clinics/{c}/members/{m}", SecurityFixtures.CLINIC_A, memberOfB)
				.cookie(this.sessions.login(SecurityFixtures.CAROL)))
			.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(404);
	}

	// erin belongs to both clinics, but as RECEPTIONIST in B — so the route is closed to her there,
	// which surprises nobody in either case. The fixture that makes I3 testable at all.
	@Test
	void erinIsNotAnAdministratorInHerSecondClinic() throws Exception {
		MvcResult result = this.mvc
			.perform(get("/api/v1/clinics/{c}/members", SecurityFixtures.CLINIC_B)
				.cookie(this.sessions.login(SecurityFixtures.ERIN)))
			.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		assertThat(result.getResponse().getContentAsString()).contains("AUTH_ROLE_INSUFFICIENT");
	}

	// The third access, at the service level: a revocation crossing clinics is not found either.
	@Test
	void aMemberOfAnotherClinicIsNotRevoked() {
		UUID memberOfB = SecurityFixtures.DAN.memberId(SecurityFixtures.CLINIC_B, ClinicRole.PRACTITIONER);
		this.tenantContext.set(SecurityFixtures.CLINIC_A);

		assertThatThrownBy(() -> this.members.revoke(memberOfB, LocalDate.now()))
			.isInstanceOf(MemberService.MemberNotFoundException.class);
		assertThat(this.jdbc.queryForObject("SELECT status FROM member WHERE id = ?", String.class, memberOfB))
			.isEqualTo("ACTIVE");
	}

	// Criterion 10 on this service: a tenant operation with no context refuses. It never falls back
	// to "no filter, therefore every clinic", which is the expensive failure of this architecture.
	@Test
	void withNoClinicContextTheOperationIsRefused() {
		assertThatThrownBy(() -> this.members.clinicTeam()).isInstanceOf(SecurityDenial.class);
		assertThatThrownBy(() -> this.members.member(UUID.randomUUID())).isInstanceOf(SecurityDenial.class);
	}

	// The read L1 depends on, and the one the exemption exists for: by account_id, across clinics,
	// and only the ACTIVE rows — a revocation has to bite on the next request, not at the next login.
	@Test
	void theReadByAccountIgnoresRevokedMembers() {
		List<Member> before = this.repository.findEffectiveOn(SecurityFixtures.ERIN.id(), LocalDate.now());
		assertThat(before).extracting(Member::getClinicId)
			.containsExactlyInAnyOrder(SecurityFixtures.CLINIC_A, SecurityFixtures.CLINIC_B);

		this.tenantContext.set(SecurityFixtures.CLINIC_B);
		this.members.revoke(SecurityFixtures.ERIN.memberId(SecurityFixtures.CLINIC_B, ClinicRole.RECEPTIONIST),
				LocalDate.now());

		assertThat(this.repository.findEffectiveOn(SecurityFixtures.ERIN.id(), LocalDate.now()))
			.extracting(Member::getClinicId)
			.containsExactly(SecurityFixtures.CLINIC_A);
		// Revoked, not deleted: the row stays as history (FR-IAM-06).
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM member WHERE account_id = ?", Integer.class,
				SecurityFixtures.ERIN.id())).isEqualTo(2);
	}

	// "In force" is a window, not a flag: a member that starts next week grants nothing today, and
	// one whose endDate has passed grants nothing even if nobody flipped its status.
	@Test
	void theReadByAccountRespectsTheStartEndWindow() {
		UUID notYet = insertMember(ClinicRole.CLINIC_ADMIN, "current_date + 10", "NULL");
		UUID expired = insertMember(ClinicRole.PRACTITIONER, "current_date - 30", "current_date - 1");

		List<Member> effective = this.repository.findEffectiveOn(SecurityFixtures.BOB.id(), LocalDate.now());

		assertThat(effective).extracting(Member::getId).doesNotContain(notYet, expired);
		assertThat(effective).extracting(Member::getRole).containsExactly(ClinicRole.RECEPTIONIST);
		// ... and the window is inclusive at both ends, or the same day would lock people out.
		assertThat(this.repository.findEffectiveOn(SecurityFixtures.BOB.id(), LocalDate.now().plusDays(10)))
			.extracting(Member::getId)
			.contains(notYet);
		assertThat(this.repository.findEffectiveOn(SecurityFixtures.BOB.id(), LocalDate.now().minusDays(1)))
			.extracting(Member::getId)
			.contains(expired);
	}

	// A revocation flips the status at once, so a future date would say "until next month" about a
	// role that is already gone. It is refused rather than recorded misleadingly.
	@Test
	void aRevocationOrRoleChangeCannotBeDatedInTheFuture() {
		UUID memberOfA = SecurityFixtures.BOB.memberId(SecurityFixtures.CLINIC_A, ClinicRole.RECEPTIONIST);
		this.tenantContext.set(SecurityFixtures.CLINIC_A);
		LocalDate tomorrow = LocalDate.now().plusDays(1);

		assertThatThrownBy(() -> this.members.revoke(memberOfA, tomorrow))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.members.changeRole(memberOfA, ClinicRole.PRACTITIONER, tomorrow))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(this.jdbc.queryForObject("SELECT status FROM member WHERE id = ?", String.class, memberOfA))
			.as("nothing changed").isEqualTo("ACTIVE");
	}

	/** A member of bob in clinic A, written straight to the table with the given window. */
	private UUID insertMember(ClinicRole role, String startDate, String endDate) {
		UUID id = UUID.randomUUID();
		this.jdbc.update("INSERT INTO member (id, account_id, clinic_id, role, start_date, end_date, status, created_by) VALUES "
				+ "(?, ?, ?, ?, " + startDate + ", " + endDate + ", 'ACTIVE', ?)", id, SecurityFixtures.BOB.id(),
				SecurityFixtures.CLINIC_A, role.name(), SecurityFixtures.CAROL.id());
		return id;
	}

	private MvcResult list(UUID clinicId) throws Exception {
		return this.mvc
			.perform(get("/api/v1/clinics/{c}/members", clinicId)
				.cookie(this.sessions.login(SecurityFixtures.CAROL)))
			.andReturn();
	}

	private boolean existsInDatabase(UUID memberId) {
		return this.jdbc.queryForObject("SELECT count(*) FROM member WHERE id = ?", Integer.class, memberId) == 1;
	}

}
