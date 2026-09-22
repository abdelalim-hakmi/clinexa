package com.clinexa.shared.security.fixtures;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.clinexa.shared.security.identity.ClinicRole;

/**
 * The one reference dataset every security test of every service is written against (guide 10.1).
 * <p>
 * It is published as a {@code test-jar} and consumed by {@code identity-service} and
 * {@code care-service}, because a cross-tenant test is only meaningful when both sides use
 * <strong>the same clinic ids</strong>: a test that passes because clinic B's row does not exist
 * is the classic silent failure of this whole family (04 §6).
 * <p>
 * Two fixtures carry most of the value, and both are here precisely because they are awkward:
 * <ul>
 * <li>{@link #ERIN} is {@code PRACTITIONER} in A <em>and</em> {@code RECEPTIONIST} in B. She is the
 * proof that a role belongs to the membership and not to the account (I3): she must see the clinical
 * record of A and not the one of B. A model that carried the role on the account cannot express her
 * at all.</li>
 * <li>{@link #P1_A} and {@link #P1_B} are the <strong>same person</strong> — same phone, same date of
 * birth — recorded in two clinics, with <strong>no link whatsoever in the database</strong>
 * ({@code SEC-04}, "Resolution"). They prove that clinic B's record never surfaces in a clinic A
 * response: not its content, not even its existence.</li>
 * </ul>
 * The rows are inserted by the tests themselves, never through an API — the {@code care} skeleton
 * has no write route at all ({@code SEC-14}).
 * <p>
 * Identifiers are fixed and readable on purpose ({@code …000a} is clinic A, {@code …00a1} is
 * alice): when a cross-tenant assertion fails, the offending id in the message says which clinic
 * it came from without a lookup.
 */
public final class SecurityFixtures {

	// ---- clinics --------------------------------------------------------------------------------

	/** Clinic A — alice, bob, carol, and erin as PRACTITIONER. */
	public static final UUID CLINIC_A = UUID.fromString("0193a000-0000-7000-8000-00000000000a");

	/** Clinic B — dan, and erin as RECEPTIONIST. Its data must never appear on the A side. */
	public static final UUID CLINIC_B = UUID.fromString("0193a000-0000-7000-8000-00000000000b");

	/** A clinic nobody is a member of: what a 403 from L1 looks like when the id is well-formed. */
	public static final UUID UNKNOWN_CLINIC = UUID.fromString("0193a000-0000-7000-8000-0000000000ff");

	// ---- accounts ---------------------------------------------------------------------------------

	/**
	 * The same password for every test account — and it never leaves the test sources.
	 * <p>
	 * Guide 5.7 asks for a documented one-minute path to a working session, because a team that
	 * cannot get one in a minute will find a way around the security instead.
	 */
	public static final String PASSWORD = "Clinexa!2026";

	/**
	 * BCrypt hash of {@link #PASSWORD}, generated once (a random salt makes it non-reproducible).
	 * {@code MotDePasseHashTest} in {@code identity-service} re-verifies it on every build, so a
	 * copy-paste accident here fails the CI rather than the login.
	 */
	public static final String PASSWORD_HASH = "$2a$10$8Y6iF6P.nfEDHiL054YKPuildZQ8IOAlV1dHmoAYivU6Culg5M/2a";

	/** Practitioner of clinic A — the "legitimate" side of every cross-tenant test. */
	public static final AccountFixture ALICE = new AccountFixture(
			UUID.fromString("0193a000-0001-7000-8000-0000000000a1"), "alice@clinic-a.ma", "Alaoui", "Alice",
			Map.of(CLINIC_A, Set.of(ClinicRole.PRACTITIONER)));

	/** Receptionist of clinic A: right clinic, no clinical access. */
	public static final AccountFixture BOB = new AccountFixture(
			UUID.fromString("0193a000-0001-7000-8000-0000000000b0"), "bob@clinic-a.ma", "Benali", "Bob",
			Map.of(CLINIC_A, Set.of(ClinicRole.RECEPTIONIST)));

	/** Admin of clinic A: proves I6 — the administrative role inherits nothing clinical. */
	public static final AccountFixture CAROL = new AccountFixture(
			UUID.fromString("0193a000-0001-7000-8000-0000000000c0"), "carol@clinic-a.ma", "Chraibi", "Carol",
			Map.of(CLINIC_A, Set.of(ClinicRole.CLINIC_ADMIN)));

	/** Practitioner of clinic B — the other side of the cross-tenant test. */
	public static final AccountFixture DAN = new AccountFixture(
			UUID.fromString("0193a000-0001-7000-8000-0000000000d0"), "dan@clinic-b.ma", "Daoudi", "Dan",
			Map.of(CLINIC_B, Set.of(ClinicRole.PRACTITIONER)));

	/** The decisive fixture: two clinics, two different roles, one account (I3). */
	public static final AccountFixture ERIN = new AccountFixture(
			UUID.fromString("0193a000-0001-7000-8000-0000000000e0"), "erin@clinexa.ma", "Elomari", "Erin",
			Map.of(CLINIC_A, Set.of(ClinicRole.PRACTITIONER), CLINIC_B, Set.of(ClinicRole.RECEPTIONIST)));

	// ---- patient records (base care, SEC-14) ----------------------------------------------------

	/** Patient P1 as clinic A knows them. */
	public static final RecordFixture P1_A = new RecordFixture(
			UUID.fromString("0193a000-0003-7000-8000-000000000a01"), CLINIC_A, "Tazi", "Nadia",
			LocalDate.of(1988, 4, 17), "+212600000001", "CNSS",
			"Asthma since childhood", "Appendectomy 2009", "Type 2 diabetes on the maternal side");

	/** A second record of clinic A: proves a listing stays inside its own clinic. */
	public static final RecordFixture P2_A = new RecordFixture(
			UUID.fromString("0193a000-0003-7000-8000-000000000a02"), CLINIC_A, "Bennani", "Youssef",
			LocalDate.of(1975, 11, 2), "+212600000002", "AMO",
			"Hypertension", "None", "None");

	/**
	 * The same person as {@link #P1_A} — same phone, same date of birth — recorded by clinic B.
	 * Nothing in the database links the two rows, and nothing should: the link would only make sense
	 * the day a patient portal exists, and it would be an additive migration then.
	 */
	public static final RecordFixture P1_B = new RecordFixture(
			UUID.fromString("0193a000-0003-7000-8000-000000000b01"), CLINIC_B, "Tazi", "Nadia",
			LocalDate.of(1988, 4, 17), "+212600000001", "Mutuelle",
			"Chronic migraines", "None", "None");

	private SecurityFixtures() {
	}

	/** Every test account, in a stable order. */
	public static List<AccountFixture> accounts() {
		return List.of(ALICE, BOB, CAROL, DAN, ERIN);
	}

	/** Every test record, in a stable order. Clinic B's rows are not optional (04 §6). */
	public static List<RecordFixture> records() {
		return List.of(P1_A, P2_A, P1_B);
	}

	/**
	 * An account and the memberships it holds. Carries no password: the hash is the same for all of
	 * them, and a fixture that looked like it could differ would invite a per-user one.
	 */
	public record AccountFixture(UUID id, String email, String lastName, String firstName,
			Map<UUID, Set<ClinicRole>> assignments) {

		public Set<ClinicRole> rolesIn(UUID clinicId) {
			return this.assignments.getOrDefault(clinicId, Set.of());
		}

		/**
		 * Deterministic member id, so a test can address one member by id — the third and last
		 * access {@code SEC-13} allows, {@code (id, clinic_id)} — without querying for it first.
		 * Derived from the triple that identifies a member, so a role added to an account never
		 * collides with the ones already there.
		 */
		public UUID memberId(UUID clinicId, ClinicRole role) {
			return UUID.nameUUIDFromBytes(
					("member:%s:%s:%s".formatted(this.id, clinicId, role)).getBytes(StandardCharsets.UTF_8));
		}

	}

	/**
	 * A patient record and its clinical volet — two tables, physically separate (LLD 20 §4.1), which
	 * is what makes the route separation of I5 impossible to bypass by mapping accident.
	 */
	public record RecordFixture(UUID id, UUID clinicId, String lastName, String firstName, LocalDate dateOfBirth,
			String phone, String coverage, String medicalHistory, String surgicalHistory,
			String familyHistory) {
	}

}
