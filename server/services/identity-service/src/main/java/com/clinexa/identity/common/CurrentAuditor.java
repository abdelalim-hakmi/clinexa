package com.clinexa.identity.common;

import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import com.clinexa.shared.security.identity.CurrentUser;
import com.clinexa.shared.security.identity.CurrentUserResolver;

/**
 * Fills {@code created_by} / {@code updated_by} from the identity contract.
 * <p>
 * It goes through {@link CurrentUserResolver} and not through {@code SecurityContextHolder}: the
 * rule that a single class in the code base knows the authentication mechanism (INV-6) applies to
 * auditing as much as to controllers. Outside a request — a migration, a scheduled job, a test —
 * there is simply no auditor, and {@code created_by} stays empty rather than being attributed to
 * whoever happened to be on the thread.
 * <p>
 * {@code member.created_by} is {@code NOT NULL}, so an insert outside a request fails rather than
 * recording an unattributed member. That is deliberate: a member is an authorization decision, and
 * an authorization decision nobody made should not exist.
 * <p>
 * {@code modifyOnCreate = false} keeps {@code updated_at} / {@code updated_by} empty until the
 * first real update, so "never modified" and "modified once, identically" stay distinguishable.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(modifyOnCreate = false)
class CurrentAuditor {

	@Bean
	AuditorAware<UUID> auditorAware(CurrentUserResolver resolver) {
		return () -> resolver.resolve().map(CurrentUser::accountId);
	}

}
