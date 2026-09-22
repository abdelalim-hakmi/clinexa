package com.clinexa.shared.security.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;

/**
 * The CSRF naming contract between the SPA and the chain, pinned where nothing can move it.
 * <p>
 * It is tested here rather than through the running application on purpose: {@code MockMvc}'s
 * {@code csrf()} post-processor installs a repository of its own for the rest of the context's
 * life, so an integration test would assert whichever repository happened to answer last. This one
 * asserts the configuration itself.
 */
class SecurityChainBuilderTest {

	/**
	 * A mismatch between these two names is the single most common cause of "every POST answers
	 * 403 and nothing explains why". They are Angular's defaults, so the SPA matches with no
	 * configuration — and they are asserted so that a future change on either side breaks a test
	 * rather than the login screen.
	 */
	@Test
	void theCsrfTokenCarriesTheNamesTheSpaExpects() {
		CsrfToken token = repository().generateToken(new MockHttpServletRequest());

		assertThat(token.getHeaderName()).isEqualTo("X-XSRF-TOKEN");
		assertThat(SecurityChainBuilder.CSRF_HEADER).isEqualTo("X-XSRF-TOKEN");
		assertThat(SecurityChainBuilder.CSRF_COOKIE).isEqualTo("XSRF-TOKEN");
	}

	/** The SPA has to read the cookie to copy it into the header — so this one is not HttpOnly. */
	@Test
	void theCsrfCookieIsReadableByJavaScript() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		var response = new org.springframework.mock.web.MockHttpServletResponse();
		var repository = repository();
		repository.saveToken(repository.generateToken(request), request, response);

		var cookie = response.getCookie(SecurityChainBuilder.CSRF_COOKIE);
		assertThat(cookie).isNotNull();
		assertThat(cookie.isHttpOnly()).isFalse();
	}

	/** Reaches the private factory the builder uses, so the test cannot drift from the code. */
	private static org.springframework.security.web.csrf.CookieCsrfTokenRepository repository() {
		try {
			var method = SecurityChainBuilder.class.getDeclaredMethod("csrfRepository");
			method.setAccessible(true);
			return (org.springframework.security.web.csrf.CookieCsrfTokenRepository) method.invoke(null);
		}
		catch (ReflectiveOperationException e) {
			throw new IllegalStateException("csrfRepository() changed signature", e);
		}
	}

}
