package com.clinexa.identity.account;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Reads of {@link Account}. A global entity — no {@code clinic_id}, no {@code @TenantId} — because
 * an identity exists before, and independently of, any clinic: the login has to find the account
 * before the tenant is known at all.
 */
public interface AccountRepository extends JpaRepository<Account, UUID> {

	/**
	 * The lookup the login performs. It matches on {@code lower(email)} because that is what the
	 * unique index is built on: a case-sensitive query would let "Alice@…" and "alice@…" both
	 * authenticate or neither, depending on how the row happened to be written.
	 * <p>
	 * The JPQL is intentional, not an oversight of I9: this entity carries no {@code @TenantId}, so
	 * there is no tenant filter to bypass here — and it is a single-row read, not a bulk statement.
	 */
	@Query("select a from Account a where lower(a.email) = lower(:email)")
	Optional<Account> findByEmailIgnoreCase(String email);

}
