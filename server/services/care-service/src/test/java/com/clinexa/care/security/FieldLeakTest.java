package com.clinexa.care.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.clinexa.care.support.CareIntegrationTest;
import com.clinexa.care.support.CareTestData;
import com.clinexa.care.support.SessionsCare;
import com.clinexa.shared.security.fixtures.SecurityFixtures;

import tools.jackson.databind.json.JsonMapper;

/**
 * <strong>F5 — Field leak</strong>, and acceptance criterion 7.
 * <p>
 * <strong>The assertion is on the serialized JSON, never on the class of the DTO.</strong> That is
 * the whole design of this family: the JSON is what leaves the server, so the JSON is the evidence.
 * A test asserting "the handler returns an {@code AdministrativeRecordDto}" would keep passing
 * through a Jackson mixin, an {@code @JsonUnwrapped}, a converter or a global serializer that put a
 * clinical field back on the wire.
 */
@CareIntegrationTest
class FieldLeakTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	SessionsCare sessions;

	@Autowired
	CareTestData data;

	@Autowired
	JsonMapper json;

	@BeforeEach
	void install() {
		this.data.install();
	}

	/**
	 * Criterion 7 — the JSON served to {@code bob} (RECEPTIONIST) contains no clinical key, and no
	 * clinical value either: a field renamed on its way out would still be a leak.
	 */
	@Test
	void criterion7TheJsonServedToBobHasNoClinicalKey() throws Exception {
		String body = this.mvc
			.perform(get("/api/v1/clinics/{c}/records/{d}/administrative", SecurityFixtures.CLINIC_A,
					SecurityFixtures.P1_A.id())
				.cookie(this.sessions.login(SecurityFixtures.BOB)))
			.andReturn()
			.getResponse()
			.getContentAsString();

		@SuppressWarnings("unchecked")
		Map<String, Object> served = this.json.readValue(body, Map.class);

		assertThat(served).as("the administrative projection is served correctly")
			.containsEntry("lastName", SecurityFixtures.P1_A.lastName())
			.containsEntry("phone", SecurityFixtures.P1_A.phone());
		assertThat(served.keySet()).as("no clinical key in the administrative response")
			.noneMatch(key -> key.toLowerCase().contains("history") || key.toLowerCase().contains("clinical")
					|| key.toLowerCase().contains("diagnosis"));
		assertThat(body).as("nor any clinical value, even under another field name")
			.doesNotContain(SecurityFixtures.P1_A.medicalHistory())
			.doesNotContain(SecurityFixtures.P1_A.surgicalHistory())
			.doesNotContain(SecurityFixtures.P1_A.familyHistory());
	}

	// The other side of the route separation: the clinical DTO carries the clinical data and none of
	// the administrative identity — two routes, two payloads, no overlap to arbitrate at runtime.
	@Test
	void theClinicalDtoDoesNotCarryTheAdministrativeIdentity() throws Exception {
		String body = this.mvc
			.perform(get("/api/v1/clinics/{c}/records/{d}/clinical", SecurityFixtures.CLINIC_A,
					SecurityFixtures.P1_A.id())
				.cookie(this.sessions.login(SecurityFixtures.ALICE)))
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(body).contains(SecurityFixtures.P1_A.medicalHistory())
			.doesNotContain(SecurityFixtures.P1_A.phone())
			.doesNotContain(SecurityFixtures.P1_A.coverage());
	}

}
