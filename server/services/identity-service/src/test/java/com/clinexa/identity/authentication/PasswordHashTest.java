package com.clinexa.identity.authentication;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.clinexa.shared.security.fixtures.SecurityFixtures;

/**
 * Keeps the test password and its hash in step — guide 5.7.
 * <p>
 * BCrypt salts randomly, so the hash cannot be recomputed and compared: it is generated once and
 * pasted into {@code SecurityFixtures}. Everything else then depends on that paste being right, and
 * a wrong one would show up as "every security test fails to sign in", which is a long way from
 * "somebody mistyped a constant". This test makes it show up as itself.
 * <p>
 * It also prints a fresh hash, which is how the next one is produced when the password changes —
 * the "one JUnit test that displays it" the guide asks for. Run it with {@code -Dtest=PasswordHashTest}
 * and copy the line it logs.
 */
class PasswordHashTest {

	@Test
	void theFixtureHashMatchesThePassword() {
		BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

		assertThat(encoder.matches(SecurityFixtures.PASSWORD, SecurityFixtures.PASSWORD_HASH))
			.as("SecurityFixtures.PASSWORD_HASH no longer matches PASSWORD."
					+ " New usable hash: %s", encoder.encode(SecurityFixtures.PASSWORD))
			.isTrue();

		assertThat(encoder.matches("a-different-password", SecurityFixtures.PASSWORD_HASH)).isFalse();
		assertThat(SecurityFixtures.PASSWORD_HASH).as("the BCrypt cost must stay the default one")
			.startsWith("$2a$10$");
	}

	@Test
	void printAUsableHash() {
		System.out.println("BCrypt hash of \"" + SecurityFixtures.PASSWORD + "\": "
				+ new BCryptPasswordEncoder().encode(SecurityFixtures.PASSWORD));
	}

}
