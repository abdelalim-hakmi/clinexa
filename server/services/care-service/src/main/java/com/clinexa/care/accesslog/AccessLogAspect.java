package com.clinexa.care.accesslog;

import java.util.UUID;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Records every read of clinical content (guide 9.1), and only once it has actually succeeded.
 * <p>
 * <strong>Why an aspect.</strong> Added now, the access log is one annotation and one table; added
 * after the fact it means instrumenting every clinical endpoint one at a time, and missing one is
 * invisible. It is the direct counterpart of {@code SEC-03}: because the record is shared between
 * every practitioner of a clinic, compartmentalisation does not exist and traceability replaces it.
 * <p>
 * <strong>Why after, not before.</strong> Logging an attempt that was then refused — by the role
 * rule, by the tenant filter, or because the record does not exist — would fill the log with
 * accesses that never happened, and the only job of this log is to explain the ones that did.
 */
@Aspect
@Component
public class AccessLogAspect {

	private final AccessLogService accessLog;

	public AccessLogAspect(AccessLogService accessLog) {
		this.accessLog = accessLog;
	}

	@Around("@annotation(logAccess)")
	public Object logAccess(ProceedingJoinPoint point, LogAccess logAccess) throws Throwable {
		Object result = point.proceed();
		if (servedContent(result)) {
			this.accessLog.record(logAccess.action(), logAccess.resource(), identifier(point, logAccess.parameter()));
		}
		return result;
	}

	/** A {@code 404} or an empty body is not an access to anything. */
	private static boolean servedContent(Object result) {
		if (result instanceof ResponseEntity<?> response) {
			return response.getStatusCode().is2xxSuccessful() && response.getBody() != null;
		}
		return result != null;
	}

	private static UUID identifier(ProceedingJoinPoint point, String parameterName) {
		String[] names = ((MethodSignature) point.getSignature()).getParameterNames();
		Object[] values = point.getArgs();
		for (int i = 0; names != null && i < names.length; i++) {
			if (parameterName.equals(names[i]) && values[i] instanceof UUID identifier) {
				return identifier;
			}
		}
		// A line without the resource id still says who read what kind of data and when. Failing
		// here would turn a logging problem into a refused clinical read.
		return null;
	}

}
