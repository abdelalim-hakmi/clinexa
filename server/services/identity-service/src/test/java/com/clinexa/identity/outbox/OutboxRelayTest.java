package com.clinexa.identity.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import com.clinexa.identity.member.MemberService;
import com.clinexa.identity.support.IdentityContainers;
import com.clinexa.identity.support.TestData;
import com.clinexa.shared.security.fixtures.SecurityFixtures;
import com.clinexa.shared.security.identity.AuthenticatedAccount;
import com.clinexa.shared.security.identity.ClinicRole;
import com.clinexa.shared.security.tenant.TenantContext;

import tools.jackson.databind.json.JsonMapper;

/**
 * The three member events, published through the outbox — with <strong>no consumer at all</strong>,
 * which is exactly what guide 8.5 asks for.
 * <p>
 * That looks like waste and is not. Publishing them from V0 costs one insert per member change
 * today, and it is what makes the eventual switch from the synchronous {@code SEC-10} call (option
 * O3) to an event-fed local replica (O4) a change of one implementation instead of a new
 * integration. The moment to do that switch is written into {@code SEC-10}: when the p95 latency of
 * the synchronous call starts eating a calling service's performance budget.
 * <p>
 * It is also the cross test that pays for the relay's I9 derogation. The relay has no request and
 * therefore no clinic, so it reads the outbox with a native query that escapes the tenant filter.
 * This class checks the two things that makes acceptable: the relay <strong>does</strong> drain both
 * clinics, and the entity read — the one every other code path uses — <strong>does not</strong>.
 */
@SpringBootTest(properties = { "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
		"spring.config.import=file:../../platform/config-server/src/main/resources/configurations/identity-service.yml",
		"clinexa.outbox.relay.enabled=true", "clinexa.outbox.relay.period=PT1H" })
@Import({ IdentityContainers.class, OutboxRelayTest.KafkaContainerConfig.class })
class OutboxRelayTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class KafkaContainerConfig {

		private static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.0");

		static {
			KAFKA.start();
		}

		@Bean
		@ServiceConnection
		ConfluentKafkaContainer kafka() {
			return KAFKA;
		}

	}

	@Autowired
	MemberService members;

	@Autowired
	OutboxRelay relay;

	@Autowired
	OutboundEventRepository outbox;

	@Autowired
	TenantContext tenantContext;

	@Autowired
	TestData data;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	JsonMapper json;

	/**
	 * The broker address comes from the ServiceConnection, not from spring.kafka.* — with a
	 * Testcontainers connection the properties are never filled, and a consumer built from them
	 * would quietly poll the wrong address and find nothing.
	 */
	@Autowired
	KafkaConnectionDetails kafka;

	@BeforeEach
	void install() {
		this.data.install();
		// member.created_by is NOT NULL by design: a member is an authorization decision, and one
		// nobody made should not exist. These service calls therefore act as a real administrator.
		SecurityContextHolder.getContext()
			.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
					new AuthenticatedAccount(SecurityFixtures.CAROL.id(), SecurityFixtures.CAROL.email(), null, true),
					null, java.util.Set.of()));
	}

	@AfterEach
	void cleanUp() {
		this.tenantContext.clear();
		SecurityContextHolder.clearContext();
	}

	// The three events of guide 8.5, in the envelope of HLD 11 §2 — every one of them carrying its
	// clinicId, because a consumer has no request and no tenant context of its own (I7).
	@Test
	void theThreeMemberEventsArePublished() {
		this.tenantContext.set(SecurityFixtures.CLINIC_A);
		UUID memberId = this.members
			.assign(SecurityFixtures.DAN.id(), ClinicRole.RECEPTIONIST, LocalDate.now())
			.getId();
		UUID afterChange = this.members.changeRole(memberId, ClinicRole.PRACTITIONER, LocalDate.now())
			.getId();
		this.members.revoke(afterChange, LocalDate.now());

		List<Map<String, Object>> envelopes = this.jdbc
			.queryForList("SELECT topic, payload FROM outbound_event ORDER BY created_at")
			.stream()
			.map(row -> Map.of("topic", row.get("topic"), "envelope",
					this.json.readValue(String.valueOf(row.get("payload")), Map.class)))
			.toList();

		assertThat(envelopes).extracting(row -> row.get("topic"))
			.containsExactly("identity.member.assigned", "identity.member.role_changed", "identity.member.revoked");
		for (Map<String, Object> row : envelopes) {
			@SuppressWarnings("unchecked")
			Map<String, Object> envelope = (Map<String, Object>) row.get("envelope");
			assertThat(envelope).containsKeys("eventId", "eventType", "eventVersion", "occurredAt", "clinicId",
					"payload");
			assertThat(envelope.get("clinicId")).isEqualTo(SecurityFixtures.CLINIC_A.toString());
		}
	}

	// The derogation, and both halves of what justifies it.
	@Test
	void theRelayDrainsBothClinicsWhereTheEntitySeesOnlyOne() throws Exception {
		this.tenantContext.set(SecurityFixtures.CLINIC_A);
		this.members.assign(SecurityFixtures.DAN.id(), ClinicRole.RECEPTIONIST, LocalDate.now());
		this.tenantContext.set(SecurityFixtures.CLINIC_B);
		this.members.assign(SecurityFixtures.BOB.id(), ClinicRole.RECEPTIONIST, LocalDate.now());

		// Still inside clinic B: the ORM read sees B's event and not A's — the filter works.
		assertThat(this.outbox.findAll()).hasSize(1);

		// The relay has no clinic at all, and must nonetheless see both. That is the derogation.
		this.tenantContext.clear();
		assertThat(this.relay).isNotNull();
		this.relay.drain();

		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM outbound_event WHERE published_at IS NULL",
				Integer.class))
			.as("the relay publishes the events of every clinic")
			.isZero();

		List<String> received = consume(2);
		assertThat(received).hasSize(2);
		assertThat(received).allSatisfy(payload -> assertThat(payload).contains("identity.member.assigned"));
	}

	/** Reads the events back from the real broker: published means published, not "saved". */
	private List<String> consume(int howMany) {
		Map<String, Object> configuration = new java.util.HashMap<>();
		configuration.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, this.kafka.getBootstrapServers());
		configuration.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
		configuration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		configuration.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		configuration.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(configuration)) {
			consumer.subscribe(List.of("identity.member.assigned"));
			List<String> received = new java.util.ArrayList<>();
			long end = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
			while (received.size() < howMany && System.currentTimeMillis() < end) {
				ConsumerRecords<String, String> batch = consumer.poll(Duration.ofSeconds(1));
				for (ConsumerRecord<String, String> record : batch) {
					received.add(record.value());
				}
			}
			return received;
		}
	}

}
