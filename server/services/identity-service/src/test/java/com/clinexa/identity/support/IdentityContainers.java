package com.clinexa.identity.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.redis.testcontainers.RedisContainer;

/**
 * The real Postgres and the real Redis every integration test of this service runs against.
 * <p>
 * <strong>Real ones, not mocks, and that is the point.</strong> The tenant filter is enforced by
 * Hibernate against a real database, the uniqueness rules by real indexes, and the append-only
 * guarantee by a real trigger. A test on an in-memory substitute would prove that the substitute
 * behaves, which is not the question. Guide 04 §6 lists mocking the tenant resolver as one of the
 * classic ways a suite turns green while production leaks.
 * <p>
 * The containers are <strong>started once for the whole build</strong> and shared by every test
 * class: Testcontainers reuses the running instance, and the JVM stops them on exit. A container per
 * class would multiply a slow step by the number of classes, and a slow security suite is one that
 * ends up being skipped.
 * <p>
 * The Postgres image is the one in {@code docker-compose.yaml}: testing against a different major
 * version than the one that runs would make partial indexes and triggers a coin toss.
 */
@TestConfiguration(proxyBeanMethods = false)
public class IdentityContainers {

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
