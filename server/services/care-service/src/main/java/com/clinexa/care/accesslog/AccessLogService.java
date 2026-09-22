package com.clinexa.care.accesslog;

import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.clinexa.shared.security.identity.ClinicRole;
import com.clinexa.shared.security.identity.CurrentUser;
import com.clinexa.shared.security.tenant.TenantContext;

/**
 * Writes one line of the access log, in its own transaction.
 * <p>
 * It is a separate bean rather than a method of {@link AccessLogAspect} on purpose:
 * {@code @Transactional} on a method the aspect called on itself would be ignored in silence — the
 * proxy is bypassed by self-invocation, the same trap guide 7.2 flags for {@code @PreAuthorize}.
 * The log line would then simply join the surrounding transaction, and disappear with it on a
 * rollback.
 * <p>
 * {@link Propagation#REQUIRES_NEW} is what makes the line survive whatever happens to the read
 * around it: the point of a log is to be there afterwards.
 */
@Service
public class AccessLogService {

	private final AccessLogRepository accessLog;

	private final CurrentUser currentUser;

	private final TenantContext tenantContext;

	public AccessLogService(AccessLogRepository accessLog, CurrentUser currentUser, TenantContext tenantContext) {
		this.accessLog = accessLog;
		this.currentUser = currentUser;
		this.tenantContext = tenantContext;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void record(String action, String resource, UUID resourceId) {
		UUID clinicId = this.tenantContext.requireClinicId();
		this.accessLog.save(new AccessLog(this.currentUser.accountId(), rolesIn(clinicId), action, resource,
				resourceId, MDC.get("traceId")));
	}

	/** The roles the access was made under, in a stable order so two lines compare. */
	private String rolesIn(UUID clinicId) {
		return this.currentUser.rolesIn(clinicId)
			.stream()
			.map(ClinicRole::name)
			.sorted()
			.reduce((first, next) -> first + "," + next)
			.orElse("");
	}

}
