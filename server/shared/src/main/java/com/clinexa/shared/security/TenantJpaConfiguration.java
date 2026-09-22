package com.clinexa.shared.security;

import java.util.Map;

import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.clinexa.shared.security.tenant.TenantContext;
import com.clinexa.shared.security.tenant.TenantIdentifierResolverBase;

/**
 * Plugs L2 into Hibernate: the resolver that feeds every {@code @TenantId} column from
 * {@link TenantContext} (guide 6.2).
 * <p>
 * It is separate from {@link SharedSecurityConfiguration} so that a service with no persistence —
 * the gateway, a future read-only facade — can take the identity primitives without dragging JPA in.
 * <p>
 * Registering the resolver as a Hibernate <em>property</em> rather than a bean is deliberate:
 * Hibernate reads it when the {@code SessionFactory} is built, and a bean discovered later would be
 * ignored in silence — the classic "the tests pass, production leaks" failure.
 */
@Configuration(proxyBeanMethods = false)
public class TenantJpaConfiguration {

	@Bean
	public TenantIdentifierResolverBase tenantIdentifierResolver(TenantContext tenantContext) {
		return new TenantIdentifierResolverBase(tenantContext);
	}

	@Bean
	public HibernatePropertiesCustomizer tenantIdentifierResolverCustomizer(TenantIdentifierResolverBase resolver) {
		return (Map<String, Object> properties) -> properties
			.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
	}

}
