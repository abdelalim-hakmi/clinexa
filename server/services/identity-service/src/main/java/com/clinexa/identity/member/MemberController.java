package com.clinexa.identity.member;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The team of a clinic — {@code CLINIC_ADMIN} only, and only its own clinic's.
 * <p>
 * These two reads exist at the foundation for a precise reason: {@link Member} is the one entity
 * without an ORM tenant filter ({@code SEC-13}), so the manual filtering that replaces it needs
 * cross tests through a real route — carol@A must be able neither to list nor to read a member of
 * clinic B. Without a route, that exemption would only be tested at the repository level, which is
 * not where it will break.
 * <p>
 * Read-only: {@code SEC-14} bounds the foundation to zero write route, so assigning, changing a role
 * and revoking are service methods exercised by tests. They will get their routes with the feature
 * that needs them.
 */
@RestController
@RequestMapping("/api/v1/clinics/{clinicId}/members")
class MemberController {

	private final MemberService members;

	MemberController(MemberService members) {
		this.members = members;
	}

	/**
	 * {@code clinicId} is in the path because that is what makes the tenant visible in a log and in
	 * a code review (the rejected alternative was an ambient header). It is <em>not</em> used to
	 * filter: L1 already validated it and the service reads it from the tenant context — trusting
	 * the path variable here would be trusting an identifier that came from the request (I4).
	 */
	@GetMapping
	List<MemberDto> team(@PathVariable UUID clinicId) {
		return this.members.clinicTeam().stream().map(MemberDto::of).toList();
	}

	@GetMapping("/{memberId}")
	ResponseEntity<MemberDto> member(@PathVariable UUID clinicId, @PathVariable UUID memberId) {
		try {
			return ResponseEntity.ok(MemberDto.of(this.members.member(memberId)));
		}
		catch (MemberService.MemberNotFoundException e) {
			// Another clinic's member and a non-existent one look the same from outside.
			return ResponseEntity.notFound().build();
		}
	}

}
