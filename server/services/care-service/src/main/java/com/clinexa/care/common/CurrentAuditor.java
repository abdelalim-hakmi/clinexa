package com.clinexa.care.common;

import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import com.clinexa.shared.security.identity.CurrentUserResolver;

/**
 * Fills {@code created_by} / {@code updated_by} from the identity contract.
 * <p>
 * It goes through {@link CurrentUserResolver} and not through {@code SecurityContextHolder}: the
 * rule that only one class in the code base reads the authentication mechanism applies to auditing
 * too (INV-6). Outside a request — a migration, a scheduled job — there is simply no auditor, and
 * the columns stay empty rather than being attributed to whoever happened to be around.
 * <p>
 * {@code modifyOnCreate = false} keeps {@code updated_at} / {@code updated_by} empty until the
 * first real update, so "never modified" and "modified once, identically" stay distinguishable.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(modifyOnCreate = false)
class CurrentAuditor {

	@Bean
	AuditorAware<UUID> auditorAware(CurrentUserResolver resolver) {
		return () -> resolver.resolve().map(user -> user.accountId()).or(Optional::empty);
	}

}
