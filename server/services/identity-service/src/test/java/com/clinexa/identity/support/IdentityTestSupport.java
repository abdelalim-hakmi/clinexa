package com.clinexa.identity.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Boots the whole service against the shared containers — the setup every security test of
 * {@code identity-service} uses.
 * <p>
 * Three properties are worth reading rather than skimming:
 * <ul>
 * <li>the Config Server and Eureka are <strong>off</strong>: a security suite that needs the
 * platform running is a suite that stops being run;</li>
 * <li>{@code configurations/identity-service.yml} is imported <strong>from disk</strong> instead, so
 * a typo in the real configuration fails here — including {@code ddl-auto: validate}, which turns
 * any drift between the entities and the Liquibase changelog into a red build;</li>
 * <li>the outbox relay is stopped: it is a scheduled job, and a background thread publishing to a
 * broker that is not there would add noise to every unrelated test.
 * {@code OutboxRelayTest} starts it on purpose, with a real Kafka.</li>
 * </ul>
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SpringBootTest(properties = { "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
		"spring.config.import=file:../../platform/config-server/src/main/resources/configurations/identity-service.yml",
		"clinexa.outbox.relay.enabled=false", "spring.jpa.properties.hibernate.generate_statistics=true" })
@AutoConfigureMockMvc
@Import(IdentityContainers.class)
public @interface IdentityTestSupport {

}
