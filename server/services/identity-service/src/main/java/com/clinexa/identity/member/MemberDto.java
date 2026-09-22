package com.clinexa.identity.member;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What {@code GET /api/v1/clinics/{c}/members} returns — CLINIC_ADMIN only.
 * <p>
 * It carries the member and the account it points at, never the account's credentials and never its
 * other memberships: an administrator of clinic A has no business learning that a colleague also
 * works in clinic B. That is not a display decision — it is the same tenant boundary as everywhere
 * else, applied to a field.
 */
public record MemberDto(UUID id, UUID accountId, String role, String status, LocalDate startDate, LocalDate endDate) {

	public static MemberDto of(Member member) {
		return new MemberDto(member.getId(), member.getAccountId(), member.getRole().name(),
				member.getStatus().name(), member.getStartDate(), member.getEndDate());
	}

}
