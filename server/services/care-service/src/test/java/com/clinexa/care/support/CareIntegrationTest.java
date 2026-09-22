package com.clinexa.care.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Boots the whole skeleton against the shared containers, with identity-service stood in for.
 * <p>
 * As on the other service, the Config Server and Eureka are off and the real
 * {@code configurations/care-service.yml} is imported from disk instead — so a typo in it fails
 * here, including {@code ddl-auto: validate}, which turns any drift between the entities and the
 * Liquibase changelog into a red build.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SpringBootTest(properties = { "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
		"spring.config.import=file:../../platform/config-server/src/main/resources/configurations/care-service.yml" })
@AutoConfigureMockMvc
@Import({ CareContainers.class, AssignmentsForTest.class })
public @interface CareIntegrationTest {

}
