package com.clinexa.identity.authentication;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * The sign-in body.
 * <p>
 * Validated so a malformed request is a {@code 400} rather than a failed authentication attempt:
 * the two are different facts, and mixing them makes a brute-force attempt harder to see in a log.
 * <p>
 * {@code password} never appears in a log, an error message or an event (LLD 21 §11.2), which is
 * also why this record has no {@code toString} of its own — the generated one is never called on a
 * path that logs, and any addition here should keep it that way.
 */
public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {

	@Override
	public String toString() {
		return "LoginRequest[email=" + this.email + "]";
	}

}
