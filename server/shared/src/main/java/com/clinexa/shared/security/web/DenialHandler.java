package com.clinexa.shared.security.web;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.clinexa.shared.security.tenant.SecurityDenial;
import com.clinexa.shared.security.tenant.SecurityErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Turns the three refusals into three distinguishable answers — the distinction guide, step 0
 * asks the code to keep: {@code 401} "I do not know who you are", {@code 403} on the tenant, and
 * {@code 403} on the role.
 * <p>
 * Without this, Spring answers {@code 403} with an empty body in the last two cases, and the only
 * way to tell a wrong-clinic refusal from a wrong-role one is to re-read the source. The
 * {@code code} field is what makes them tellable apart in a log, months later.
 * <p>
 * It is also what stops the default {@code 401} from carrying a {@code WWW-Authenticate: Basic}
 * header, which would make a browser pop a native credentials dialog in front of the SPA.
 */
public final class DenialHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final ProblemResponse problemResponse;

	public DenialHandler(ProblemResponse problemResponse) {
		this.problemResponse = problemResponse;
	}

	/** No session, or a session that is no longer valid (criterion 1). */
	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		this.problemResponse.write(request, response, HttpStatus.UNAUTHORIZED,
				SecurityErrorCode.AUTH_NOT_AUTHENTICATED);
	}

	/**
	 * Authenticated, but refused. Which refusal it is comes from the exception when the code threw
	 * a {@link SecurityDenial}; a plain {@code AccessDeniedException} — what the matrix produces
	 * when a role is not allowed on a route — is by definition the role case.
	 */
	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		SecurityErrorCode code = (accessDeniedException instanceof SecurityDenial denial) ? denial.code()
				: SecurityErrorCode.AUTH_ROLE_INSUFFICIENT;
		this.problemResponse.write(request, response, HttpStatus.FORBIDDEN, code);
	}

}
