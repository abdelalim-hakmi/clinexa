package com.clinexa.shared.security.web;

import java.util.function.Supplier;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The CSRF handling a single-page application needs — the recipe from the Spring Security reference
 * ("Configure CSRF for a Single-Page Application").
 * <p>
 * <strong>Why CSRF is on at all.</strong> {@code SEC-01} chose an opaque session cookie over a
 * self-contained token, because that is what makes revocation immediate. The counterpart is not
 * optional: a cookie is attached by the browser to <em>any</em> request to the origin, including one
 * forged by another site — which an {@code Authorization: Bearer} header is not. Disabling CSRF
 * "as in the tutorials" is the single most common way to undo that choice.
 * <p>
 * Two details make it work with Angular's built-in interceptor:
 * <ul>
 * <li>{@link #handle} calls {@code csrfToken.get()} so the token is rendered and the
 * {@code XSRF-TOKEN} cookie is actually written on a plain {@code GET} — with the deferred loading
 * of Spring Security 6+, nothing would send it otherwise, and every {@code POST} would answer
 * {@code 403} with no clear reason;</li>
 * <li>{@link #resolveCsrfTokenValue} reads the header as-is (the raw token Angular copies from the
 * cookie) and falls back to the XOR-masked form for the {@code _csrf} request parameter, which is
 * what a plain HTML form posts.</li>
 * </ul>
 * One more condition lives outside this class: the SPA and the gateway must be <strong>same
 * origin</strong>, or Angular attaches no header at all. In development the proxy
 * {@code client/proxy.conf.json} guarantees it — the browser only ever talks to {@code :4200}.
 */
public final class SpaCsrfHandler implements CsrfTokenRequestHandler {

	private final CsrfTokenRequestHandler raw = new CsrfTokenRequestAttributeHandler();

	private final CsrfTokenRequestHandler masked = new XorCsrfTokenRequestAttributeHandler();

	@Override
	public void handle(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response,
			Supplier<CsrfToken> csrfToken) {
		this.masked.handle(request, response, csrfToken);
		// Renders the token now, so the XSRF-TOKEN cookie exists before the first POST.
		csrfToken.get();
	}

	@Override
	public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
		return StringUtils.hasText(request.getHeader(csrfToken.getHeaderName()))
				? this.raw.resolveCsrfTokenValue(request, csrfToken)
				: this.masked.resolveCsrfTokenValue(request, csrfToken);
	}

}
