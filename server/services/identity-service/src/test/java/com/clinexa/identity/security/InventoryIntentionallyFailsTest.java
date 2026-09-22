package com.clinexa.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.clinexa.identity.support.IdentityContainers;
import com.clinexa.shared.security.fixtures.AuthorizationMatrix;
import com.clinexa.shared.security.fixtures.EndpointInventory;

/**
 * Proof that INV-1 actually fails — guide 10.8.
 * <p>
 * "Add an undeclared endpoint on purpose, check that the CI goes red, then remove it." Doing that by
 * hand once proves it on the day it is done and never again; the check itself then becomes a test
 * nobody knows still works. So the undeclared endpoint lives here permanently, in a context of its
 * own, and the assertion is <strong>inverted</strong>: this class fails if INV-1 stops catching it.
 * <p>
 * The extra endpoint is a plausible one rather than a silly one — a debug route, which is exactly
 * the kind INV-1 exists to catch, and exactly the kind that gets added late on a Friday.
 * <p>
 * It also checks the second half of the promise: the undeclared route is not merely reported, it is
 * <strong>refused</strong> at runtime by the {@code denyAll()} the shared builder appends (I1).
 */
@SpringBootTest(properties = { "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
		"spring.config.import=file:../../platform/config-server/src/main/resources/configurations/identity-service.yml",
		"clinexa.outbox.relay.enabled=false" })
@AutoConfigureMockMvc
@Import({ IdentityContainers.class, InventoryIntentionallyFailsTest.UndeclaredEndpoint.class })
class InventoryIntentionallyFailsTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class UndeclaredEndpoint {

		@RestController
		static class DebugController {

			@GetMapping("/api/v1/debug/etat-interne")
			String internalState() {
				return "this is exactly the kind of route INV-1 must catch";
			}

		}

	}

	@Autowired
	ApplicationContext context;

	@Autowired
	MockMvc mvc;

	@Test
	void inv1CatchesAnEndpointAddedOutsideTheMatrix() {
		assertThat(EndpointInventory.exposedPaths(this.context)).contains("/api/v1/debug/etat-interne");
		assertThat(AuthorizationMatrix.declaredRoutes())
			.as("if this assertion fails, INV-1 has stopped catching undeclared routes")
			.doesNotContain("/api/v1/debug/etat-interne");
	}

	// I1 in action: an undeclared route is refused, not merely reported. That is what makes the
	// last line of the chain worth more than a comment.
	@Test
	void anEndpointOutsideTheMatrixIsRefusedByDenyAll() throws Exception {
		assertThat(this.mvc.perform(get("/api/v1/debug/etat-interne")).andReturn().getResponse().getStatus())
			.isEqualTo(401);
	}

}
