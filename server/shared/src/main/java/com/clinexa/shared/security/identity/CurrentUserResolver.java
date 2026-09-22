package com.clinexa.shared.security.identity;

import java.util.Optional;

/**
 * The <strong>only</strong> place in the code base that knows which authentication mechanism is in
 * force (guide 5.5). One implementation per mechanism, selected by configuration, never by service:
 * {@link SessionCurrentUserResolver} today (opaque session cookie + Redis), an OIDC one after
 * {@code SEC-12}. Migrating then costs a version bump of {@code shared}, not a rewrite per service.
 */
public interface CurrentUserResolver {

	/** The current identity, or empty when the request carries no authenticated principal. */
	Optional<CurrentUser> resolve();

}
