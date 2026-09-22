package com.clinexa.identity.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the scheduled jobs of this service — today, exactly one: the outbox relay.
 * <p>
 * It is a configuration class of its own rather than an annotation on the application class, so that
 * what runs in the background is a deliberate, searchable decision. A scheduled job is a code path
 * with <strong>no HTTP request and therefore no tenant context</strong> (I7): every one added here
 * must either carry its {@code clinicId} explicitly, or hold a written derogation like
 * {@code OutboxRelay} does.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfiguration {

}
