package com.clinexa.identity.authentication;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.clinexa.identity.account.Account;
import com.clinexa.identity.account.AccountRepository;
import com.clinexa.identity.common.ActivityStatus;
import com.clinexa.shared.security.identity.AuthenticatedAccount;

/**
 * Turns an email into the principal the rest of the system uses (guide 5.1).
 * <p>
 * It builds an {@link AuthenticatedAccount} — which carries the identity and <strong>no member and
 * no authority</strong>. That is not an omission to fix later: a session lives for hours and a
 * member can be revoked in a second, so keeping roles in the principal would let a revoked person
 * keep working until their session expired (guide 4.4). Roles are read per request by L1 and granted
 * for that request only.
 * <p>
 * The account is looked up on {@code lower(email)}, matching the unique index, and the password
 * hash is BCrypt. A deactivated account is loaded and reported as disabled rather than hidden, so
 * Spring Security produces a proper {@code DisabledException} instead of "bad credentials" — the two
 * are different facts, and only one of them means "try again".
 */
@Service
class AccountUserDetailsService implements UserDetailsService {

	private final AccountRepository accounts;

	AccountUserDetailsService(AccountRepository accounts) {
		this.accounts = accounts;
	}

	@Override
	@Transactional(readOnly = true)
	public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
		Account account = this.accounts.findByEmailIgnoreCase(email)
			.orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
		return new AuthenticatedAccount(account.getId(), account.getEmail(), account.getPasswordHash(),
				account.getStatus() == ActivityStatus.ACTIVE);
	}

}
