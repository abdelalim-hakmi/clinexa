package com.clinexa.care.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.redis.testcontainers.RedisContainer;

/**
 * The real Postgres and the real Redis every integration test of {@code care-service} runs against.
 * <p>
 * Real ones rather than substitutes, because what is being tested <em>is</em> the engine: the tenant
 * filter is Hibernate's, the composite foreign key and the append-only trigger are Postgres's, and
 * the shared session is Redis's. Mocking the tenant resolver in an integration test means testing
 * the mock — one of the classic ways a security suite turns green while production leaks (04 §6).
 * <p>
 * Started once for the whole build and shared by every class; the images are the ones in
 * {@code docker-compose.yaml}, so a partial index or a trigger never behaves differently here.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CareContainers {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm");

	private static final RedisContainer REDIS = new RedisContainer("redis:8.0-alpine");

	static {
		POSTGRES.start();
		REDIS.start();
	}

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgres() {
		return POSTGRES;
	}

	@Bean
	@ServiceConnection("redis")
	RedisContainer redis() {
		return REDIS;
	}

}
