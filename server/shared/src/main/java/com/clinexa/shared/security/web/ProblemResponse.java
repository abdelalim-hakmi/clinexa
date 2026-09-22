package com.clinexa.shared.security.web;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import com.clinexa.shared.security.tenant.SecurityErrorCode;
import tools.jackson.databind.json.JsonMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes a security refusal as <em>Problem Details</em> (RFC 7807), in the single error shape LLD
 * 21 §3 defines for the whole API.
 * <p>
 * Two rules from LLD 21 §3.2 are what this class is really for, and both are easy to break by being
 * helpful: <strong>a {@code 403} never says whether the resource exists</strong> — otherwise the
 * error becomes an oracle — and <strong>no message ever contains data from another clinic</strong>.
 * So the body is built from the {@link SecurityErrorCode} alone: a fixed title, a fixed sentence
 * for the end user, the stable {@code code} for the client, and the {@code traceId} that lets
 * support join the screen to the server trace.
 * <p>
 * The trace id is read from the logging MDC, which Micrometer Tracing fills, rather than from a
 * {@code Tracer} bean: it keeps {@code shared} free of a tracing dependency, and a service without
 * tracing simply omits the field.
 */
public final class ProblemResponse {

	private static final String BASE_TYPE = "https://api.clinexa.ma/errors/";

	private final JsonMapper objectMapper;

	public ProblemResponse(JsonMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
			SecurityErrorCode code) throws IOException {
		if (response.isCommitted()) {
			return;
		}
		ProblemDetail problem = ProblemDetail.forStatus(status);
		problem.setType(URI.create(BASE_TYPE + code.name().toLowerCase().replace('_', '-')));
		problem.setTitle(code.title());
		problem.setDetail(code.detail());
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("code", code.name());
		traceId().ifPresent(id -> problem.setProperty("traceId", id));

		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		this.objectMapper.writeValue(response.getOutputStream(), problem);
	}

	private static Optional<String> traceId() {
		return Optional.ofNullable(MDC.get("traceId")).filter(id -> !id.isBlank());
	}

}
