package com.clinexa.identity.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.clinexa.identity.clinic.Clinic;
import com.clinexa.identity.clinic.City;
import com.clinexa.identity.account.Account;
import com.clinexa.identity.member.Member;
import com.clinexa.identity.outbox.OutboundEvent;
import com.clinexa.identity.settings.ConsultationReason;
import com.clinexa.identity.settings.ClosurePeriod;
import com.clinexa.identity.practitioner.Practitioner;
import com.clinexa.identity.practitioner.Specialty;
import com.clinexa.identity.support.IdentityTestSupport;
import com.clinexa.shared.security.identity.AuthenticatedAccount;
import com.clinexa.shared.security.identity.ClinicRole;

import jakarta.persistence.EntityManagerFactory;

/**
 * The schema and the entities, checked against a real Postgres.
 * <p>
 * The context is booted with {@code ddl-auto: validate} taken from the service's own Config Server
 * file, so any drift between an entity and the Liquibase changelog fails here rather than at the
 * next deployment. Liquibase itself runs for real, which is what makes a wrong changelog path a
 * build failure too.
 */
@IdentityTestSupport
class SchemaAndEntitiesTest {

	private static final UUID AUDITOR = UUID.fromString("0193a000-0001-7000-8000-00000000f001");

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	EntityManagerFactory emf;

	/**
	 * {@code member.created_by} is {@code NOT NULL}: a member is an authorization decision, and one
	 * nobody made should not exist. So the tests authenticate, exactly as a request would.
	 */
	@BeforeEach
	void authenticate() {
		SecurityContextHolder.getContext()
			.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
					new AuthenticatedAccount(AUDITOR, "auditeur@clinexa.ma", null, true), null, Set.of()));
	}

	@AfterEach
	void signOut() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void contextLoads() {
	}

	// ---- schema (Liquibase) ----

	// I3: the role belongs to the (account, clinic) link, never to the account alone.
	@Test
	void accountHasNoRoleColumn() {
		List<String> columns = this.jdbc.queryForList(
				"SELECT column_name FROM information_schema.columns WHERE table_name = 'account'", String.class);
		assertThat(columns).isNotEmpty().doesNotContain("role");
	}

	// Tenant discriminant: a member row without clinic_id would belong to everyone or to no one.
	@Test
	void memberRequiresAClinic() {
		UUID account = insertAccount("nocabinet@example.com");
		assertThatThrownBy(() -> this.jdbc.update(
				"INSERT INTO member (id, account_id, clinic_id, role, start_date, created_by) VALUES (?, ?, NULL, 'PRACTITIONER', now(), ?)",
				UUID.randomUUID(), account, account)).isInstanceOf(DataIntegrityViolationException.class);
	}

	// An account may cumulate roles in a clinic, never the same ACTIVE role twice; a REVOKED one may repeat.
	@Test
	void sameActiveRoleTwiceIsRefused() {
		UUID account = insertAccount("dup@example.com");
		UUID clinic = insertClinic("ICE-DUP");
		insertMember(account, clinic, "PRACTITIONER", "ACTIVE");
		insertMember(account, clinic, "RECEPTIONIST", "ACTIVE");
		assertThatThrownBy(() -> insertMember(account, clinic, "PRACTITIONER", "ACTIVE"))
			.isInstanceOf(DataIntegrityViolationException.class);
		insertMember(account, clinic, "PRACTITIONER", "REVOKED");
	}

	@Test
	void emailIsUniqueRegardlessOfCase() {
		insertAccount("Case@Example.com");
		assertThatThrownBy(() -> insertAccount("case@example.com"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void anUnknownRoleIsRefused() {
		UUID account = insertAccount("badrole@example.com");
		UUID clinic = insertClinic("ICE-BADROLE");
		assertThatThrownBy(() -> insertMember(account, clinic, "PATIENT", "ACTIVE"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ---- entities (Hibernate) ----

	@Test
	void everyEntityRoundTrips() {
		String suffix = UUID.randomUUID().toString();
		City city = new City("casablanca-" + suffix, "Casablanca");
		Specialty specialty = new Specialty("pediatrics-" + suffix, "Pediatrics");
		Account account = new Account("  Dr.Idrissi-" + suffix + "@Example.COM ", "hash", "Idrissi", "Salma");
		Clinic clinic = new Clinic("Idrissi Clinic", "ICE-" + suffix);
		clinic.setCity(city);
		inTenant(UUID.randomUUID(), s -> {
			s.persist(city);
			s.persist(specialty);
			s.persist(account);
			s.persist(clinic);
			return null;
		});
		UUID clinicId = clinic.getId();

		Practitioner practitioner = new Practitioner(account.getId(), "ORD-" + suffix);
		practitioner.setLanguages(List.of("fr", "ar"));
		practitioner.getSpecialties().add(specialty);
		Member member = new Member(account.getId(), clinicId, ClinicRole.PRACTITIONER, LocalDate.of(2026, 9, 1));
		ConsultationReason reason = new ConsultationReason("First consultation", 30);
		reason.setIndicativePrice(new BigDecimal("250.00"));
		ClosurePeriod closure = new ClosurePeriod(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 31),
				"Holidays");
		OutboundEvent event = new OutboundEvent("identity.member.assigned", clinicId.toString(),
				"{\"memberId\":\"x\"}", null);
		inTenant(clinicId, s -> {
			s.persist(practitioner);
			s.persist(member);
			s.persist(reason);
			s.persist(closure);
			s.persist(event);
			return null;
		});

		inTenant(clinicId, s -> {
			Account a = s.find(Account.class, account.getId());
			assertThat(a.getEmail()).isEqualTo("dr.idrissi-" + suffix + "@example.com");
			assertThat(a.getCreatedAt()).isNotNull();
			assertThat(a.getUpdatedAt()).isNull();
			assertThat(a.getCreatedBy()).isEqualTo(AUDITOR);

			Clinic cl = s.find(Clinic.class, clinicId);
			assertThat(cl.getTimezone()).isEqualTo(ZoneId.of("Africa/Casablanca"));
			assertThat(cl.getCity().getName()).isEqualTo("Casablanca");

			Practitioner p = s.find(Practitioner.class, practitioner.getId());
			assertThat(p.getLanguages()).containsExactly("fr", "ar");
			assertThat(p.getSpecialties()).extracting(Specialty::getSlug).containsExactly(specialty.getSlug());

			Member m = s.find(Member.class, member.getId());
			assertThat(m.getRole()).isEqualTo(ClinicRole.PRACTITIONER);
			assertThat(m.getClinicId()).isEqualTo(clinicId);
			assertThat(m.getCreatedBy()).isEqualTo(AUDITOR);

			ConsultationReason r = s.find(ConsultationReason.class, reason.getId());
			assertThat(r.getClinicId()).isEqualTo(clinicId);
			assertThat(r.getIndicativePrice()).isEqualByComparingTo("250.00");
			assertThat(r.isActive()).isTrue();

			ClosurePeriod f = s.find(ClosurePeriod.class, closure.getId());
			assertThat(f.getClinicId()).isEqualTo(clinicId);
			assertThat(f.getEndDate()).isEqualTo(LocalDate.of(2026, 12, 31));

			assertThat(s.find(OutboundEvent.class, event.getId()).getClinicId()).isEqualTo(clinicId);
			return null;
		});

		// The payload must be a JSON object in the jsonb column, not a JSON-encoded string.
		assertThat(this.jdbc.queryForObject("SELECT jsonb_typeof(payload) FROM outbound_event WHERE id = ?",
				String.class, event.getId())).isEqualTo("object");
	}

	// @TenantId: a session bound to clinic B sees nothing of clinic A, by id or by query.
	@Test
	void theTenantHidesTheRowsOfOtherClinics() {
		Clinic a = newClinic();
		Clinic b = newClinic();
		ConsultationReason reason = new ConsultationReason("Suivi", 15);
		inTenant(a.getId(), s -> {
			s.persist(reason);
			return null;
		});

		inTenant(b.getId(), s -> {
			assertThat(s.find(ConsultationReason.class, reason.getId())).isNull();
			assertThat(s.createSelectionQuery("from ConsultationReason", ConsultationReason.class).getResultList())
				.isEmpty();
			return null;
		});
		inTenant(a.getId(), s -> {
			assertThat(s.find(ConsultationReason.class, reason.getId())).isNotNull();
			return null;
		});
	}

	// Second-level cache: reference data only. Account / Member must never be cached (immediate revocation).
	@Test
	void onlyReferenceDataIsCached() {
		SessionFactory sf = this.emf.unwrap(SessionFactory.class);
		Statistics stats = sf.getStatistics();
		UUID tenant = UUID.randomUUID();
		String suffix = UUID.randomUUID().toString();
		Specialty specialty = new Specialty("cardio-" + suffix, "Cardiologie");
		Account account = new Account("cache-" + suffix + "@example.com", "hash", "Nom", "Prenom");
		inTenant(tenant, s -> {
			s.persist(specialty);
			s.persist(account);
			return null;
		});
		sf.getCache().evictAllRegions();
		stats.clear();

		inTenant(tenant, s -> s.find(Specialty.class, specialty.getId())); // miss, fills the cache
		inTenant(tenant, s -> s.find(Specialty.class, specialty.getId())); // hit
		inTenant(tenant, s -> s.find(Account.class, account.getId()));
		inTenant(tenant, s -> s.find(Account.class, account.getId()));

		// One put and one hit in total: had Account been cached, its finds would add a put and a hit.
		// (Hibernate keys every entry with the session's tenant id: each clinic caches its own copy
		// of the reference rows — fine for a few hundred rows, so no cross-clinic assertion here.)
		assertThat(stats.getSecondLevelCachePutCount()).isEqualTo(1);
		assertThat(stats.getSecondLevelCacheHitCount()).isEqualTo(1);
	}

	// ---- helpers ----

	private <T> T inTenant(UUID tenant, Function<Session, T> work) {
		SessionFactory sf = this.emf.unwrap(SessionFactory.class);
		try (Session session = sf.withOptions().tenantIdentifier(tenant).openSession()) {
			Transaction tx = session.beginTransaction();
			try {
				T result = work.apply(session);
				tx.commit();
				return result;
			}
			catch (RuntimeException e) {
				tx.rollback();
				throw e;
			}
		}
	}

	private Clinic newClinic() {
		Clinic clinic = new Clinic("Cabinet", "ICE-" + UUID.randomUUID());
		inTenant(UUID.randomUUID(), s -> {
			s.persist(clinic);
			return null;
		});
		return clinic;
	}

	private UUID insertAccount(String email) {
		UUID id = UUID.randomUUID();
		this.jdbc.update(
				"INSERT INTO account (id, email, password_hash, last_name, first_name) VALUES (?, ?, 'x', 'Nom', 'Prenom')",
				id, email);
		return id;
	}

	private UUID insertClinic(String ice) {
		UUID id = UUID.randomUUID();
		this.jdbc.update("INSERT INTO clinic (id, legal_name, ice) VALUES (?, 'Cabinet', ?)", id, ice);
		return id;
	}

	private void insertMember(UUID account, UUID clinic, String role, String status) {
		this.jdbc.update("""
				INSERT INTO member (id, account_id, clinic_id, role, start_date, status, created_by)
				VALUES (?, ?, ?, ?, now(), ?, ?)
				""", UUID.randomUUID(), account, clinic, role, status, account);
	}

}
