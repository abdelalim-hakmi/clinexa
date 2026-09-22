package com.clinexa.care.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import com.clinexa.care.record.PatientRecord;
import com.clinexa.care.record.PatientRecordRepository;
import com.clinexa.care.support.AssignmentsForTest;
import com.clinexa.care.support.CareContainers;
import com.clinexa.care.support.CareTestData;
import com.clinexa.shared.security.fixtures.SecurityFixtures;
import com.clinexa.shared.security.tenant.SecurityDenial;
import com.clinexa.shared.security.tenant.TenantContext;

import tools.jackson.databind.json.JsonMapper;

/**
 * <strong>Criterion 9</strong> — an event consumed without a {@code clinicId} fails; it does not
 * write.
 * <p>
 * This is the second criterion that never shows itself while everything works, and the reason it
 * needs a consumer at all: a Kafka listener runs on <strong>its own thread, outside any HTTP
 * request</strong>, so it starts with no tenant context (I7). The dangerous behaviour is not that it
 * refuses — it is that it might quietly succeed, writing a row with no clinic, or reading every
 * clinic's rows because nothing filtered them.
 * <p>
 * The rule this test pins down is guide 6.3's: {@code clinicId} travels in the event envelope, and
 * the consumer re-positions it <strong>explicitly</strong> before touching a repository. No envelope
 * clinic, no work.
 * <p>
 * The consumer lives here, in {@code src/test}: no business consumer exists at the foundation
 * ({@code SEC-14}), and inventing one to satisfy a test would be adding scope through the back door.
 */
@SpringBootTest(properties = { "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
		"spring.config.import=file:../../platform/config-server/src/main/resources/configurations/care-service.yml" })
@Import({ CareContainers.class, AssignmentsForTest.class, ConsumerWithoutTenantTest.KafkaContainerConfig.class })
class ConsumerWithoutTenantTest {

	private static final String TOPIC = "test.care.tenant";

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

	/**
	 * The rule of I7, written as a consumer would write it: read the clinic from the envelope, run
	 * the work inside it, and fail if it is not there.
	 */
	static final class TestConsumer {

		private final TenantContext tenantContext;

		private final PatientRecordRepository records;

		private final JsonMapper json;

		TestConsumer(TenantContext tenantContext, PatientRecordRepository records, JsonMapper json) {
			this.tenantContext = tenantContext;
			this.records = records;
			this.json = json;
		}

		/** @return how many patient records the event's clinic holds */
		@SuppressWarnings("unchecked")
		long process(String envelopeJson) {
			Map<String, Object> envelope = this.json.readValue(envelopeJson, Map.class);
			Object clinic = envelope.get("clinicId");
			// No clinic in the envelope: refuse. Never "carry on without a filter".
			UUID clinicId = (clinic == null) ? null : UUID.fromString(clinic.toString());
			return this.tenantContext.runIn(clinicId, () -> this.records.count());
		}

	}

	@Autowired
	TenantContext tenantContext;

	@Autowired
	PatientRecordRepository records;

	@Autowired
	JsonMapper json;

	@Autowired
	CareTestData data;

	@Autowired
	KafkaTemplate<String, String> kafka;

	private TestConsumer consumer;

	@BeforeEach
	void install() {
		this.data.install();
		this.consumer = new TestConsumer(this.tenantContext, this.records, this.json);
	}

	@AfterEach
	void clear() {
		this.tenantContext.clear();
	}

	// Criterion 9: no clinicId in the envelope, so the handler refuses — and reads nothing.
	@Test
	void criterion9AnEventWithNoClinicFails() {
		String noClinic = """
				{"eventId":"%s","eventType":"test.no-clinic","eventVersion":1,"payload":{}}
				""".formatted(UUID.randomUUID());

		assertThatThrownBy(() -> this.consumer.process(noClinic)).isInstanceOf(SecurityDenial.class);
		assertThat(this.tenantContext.clinicId()).as("the context was not left positioned").isEmpty();
	}

	// With a clinic, the same handler works — and sees that clinic only. The refusal above is
	// therefore about the missing tenant, not about a handler that cannot work at all.
	@Test
	void withAClinicTheConsumerSeesThatClinicOnly() {
		assertThat(this.consumer.process(envelope(SecurityFixtures.CLINIC_A))).isEqualTo(2);
		assertThat(this.consumer.process(envelope(SecurityFixtures.CLINIC_B))).isEqualTo(1);
	}

	// The context does not survive the handler: a pooled consumer thread must not inherit the
	// clinic of the previous message.
	@Test
	void theContextDoesNotSurviveTheMessage() {
		this.consumer.process(envelope(SecurityFixtures.CLINIC_A));
		assertThat(this.tenantContext.clinicId()).isEmpty();
		assertThat(this.records.findAll()).as("outside a context, no data — never everything").isEmpty();
	}

	// End to end through a real broker: the same envelope, delivered by Kafka rather than by hand,
	// on a thread that never saw an HTTP request.
	@Test
	void onARealBrokerTheBehaviourIsTheSame() throws Exception {
		this.kafka.send(TOPIC, SecurityFixtures.CLINIC_A.toString(), envelope(SecurityFixtures.CLINIC_A))
			.get(30, TimeUnit.SECONDS);

		String received = firstMessage();
		assertThat(received).isNotNull();

		long seen = java.util.concurrent.CompletableFuture.supplyAsync(() -> this.consumer.process(received))
			.get(10, TimeUnit.SECONDS);
		assertThat(seen).isEqualTo(2);
	}

	private String envelope(UUID clinicId) {
		return """
				{"eventId":"%s","eventType":"test.with-clinic","eventVersion":1,"clinicId":"%s","payload":{}}
				""".formatted(UUID.randomUUID(), clinicId);
	}

	private String firstMessage() {
		Map<String, Object> configuration = new java.util.HashMap<>();
		configuration.put(org.apache.kafka.clients.consumer.ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
				KafkaContainerConfig.KAFKA.getBootstrapServers());
		configuration.put(org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
		configuration.put(org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		configuration.put(org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
				org.apache.kafka.common.serialization.StringDeserializer.class);
		configuration.put(org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
				org.apache.kafka.common.serialization.StringDeserializer.class);
		try (var consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(configuration)) {
			consumer.subscribe(java.util.List.of(TOPIC));
			long end = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
			while (System.currentTimeMillis() < end) {
				var batch = consumer.poll(Duration.ofSeconds(1));
				for (var record : batch) {
					return record.value();
				}
			}
			return null;
		}
	}

}
