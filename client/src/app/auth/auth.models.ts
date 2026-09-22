/**
 * The contract the front end reads — mirrors `MeDto` on the `identity-service` side.
 *
 * What the client knows about its rights is **never** what grants them: every call goes back
 * through L1, which re-reads assignments from the database and re-checks the clinic in the path.
 * These types are for displaying the right thing, not deciding — a client that lied to itself
 * would get a 403.
 */

/** Clinic roles. Disjoint, never hierarchical (I6): `CLINIC_ADMIN` does not subsume anything. */
export type ClinicRole = 'RECEPTIONIST' | 'PRACTITIONER' | 'CLINIC_ADMIN';

/** A clinic where the account can act, and the roles it holds there — several is the normal case. */
export interface ClinicAssignment {
  readonly clinicId: string;
  readonly roles: readonly ClinicRole[];
}

/** Who I am, and where I can work (`GET /api/v1/me`, FR-IAM-07). */
export interface Me {
  readonly accountId: string;
  readonly email: string;
  readonly clinics: readonly ClinicAssignment[];
}

/** The body of an API error — Problem Details (RFC 7807), LLD 21 §3. */
export interface Problem {
  readonly title: string;
  /** Sentence meant for the end user, already translated server-side. */
  readonly detail: string;
  readonly status: number;
  /** Stable, never renamed: the client branches behavior on it. */
  readonly code?: string;
  /** Links the screen to the server trace, for support. */
  readonly traceId?: string;
}
