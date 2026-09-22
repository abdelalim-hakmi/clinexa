# Reading the security foundation's code — a 45-minute walkthrough

> For whoever just received a lot of code at once. This file doesn't repeat the *why* (see
> `authentication.md`, `tenant-isolation.md`, `tests.md`): it says **what order to open files in**,
> and what to take away from each.

## First: why `care-service` exists

It's not an add-on. The guide requires it: **`SEC-14`** (1.12) and step 3.10. Without a single
business table, isolation between clinics has nothing to protect — so nothing to test. `care` is the
strict minimum for that: 2 tables (`patient_record`, `clinical_record`), 2 reads, the access log, **zero
writes, zero UI**. Any further route requires an ADR.

## The request, end to end

```
Angular ─► gateway ─► service ─► [1 session] ─► [2 TenantFilter · L1] ─► [3 matrix] ─► [4 denyAll]
                                                                                            │
                     DB ◄─ [7 constraints · L4] ◄─ [6 Hibernate @TenantId · L2] ◄─ controller ◄┘
```

The gateway only routes (cookie forwarded as-is). **Each service** rereads the session from Redis
and decides on its own.

## Reading order

| # | File (under `server/`) | One-sentence takeaway |
|---|---|---|
| 1 | `shared/…/identity/CurrentUser` + `AuthenticatedAccount` | The identity contract. The principal carries **neither roles nor assignments**: a revocation must count on the very next request. |
| 2 | `shared/…/web/SecurityChainBuilder` | The shared chain (💡 a `SecurityFilterChain`: the sequence of servlet filters Spring Security runs on every request). It **appends** `denyAll()` itself — no service can forget it. CSRF is on, and it lets through the internal `DispatcherType.ERROR` dispatch (💡 the redirect to `/error` the container makes on an exception, distinct from a real request) — otherwise a 400/500 would come back as `403`. |
| 3 | `shared/…/tenant/TenantFilter` | **L1.** The clinic comes from the URL, checked against the assignments (otherwise 403); this is where roles become rights, **for this request only**. 💡 The filter temporarily swaps the `SecurityContextHolder` (where Spring Security keeps the current user, by default in a `ThreadLocal`) for a copy carrying these roles, and restores the original in a `finally` — none of this touches the session stored in Redis. |
| 4 | `shared/…/tenant/TenantContext` + `TenantIdentifierResolverBase` | **L2.** The current clinic, per thread (💡 a `ThreadLocal`). Hibernate reads it through `CurrentTenantIdentifierResolver` **when the JPA session opens**, not on every access — that's what triggers `@TenantId` (💡 a Hibernate annotation that adds the column to every `SELECT`/`INSERT` on its own). With no context, it returns an **impossible** clinic (`NONE`), never "no filter". |
| 5 | `shared/…/identity/SessionCurrentUserResolver` + `assignment/AssignmentsHttpProvider` | Where roles come from: a call to `identity` (`SEC-10`), memoized — 💡 in `RequestAttributes`, not a *request-scoped* bean that would break outside an HTTP request — for the duration of **one** request, and a `403` if `identity` is unreachable. |
| 6 | `identity-service/…/common/IdentitySecurityChainConfiguration` | identity's **matrix**, one line per route, narrowest to widest — 💡 `authorizeHttpRequests` stops at the **first matching rule**, with no sorting and no warning if a broad rule further up shadows a narrower one placed after it. |
| 7 | `identity-service/…/authentication/AuthenticationController` | The only place that creates a session. 💡 Since Spring Security 6, a login done by a controller (as here) must save the `SecurityContext` itself — and call the anti-fixation strategy (`SessionAuthenticationStrategy`) itself to change the session id, otherwise an id planted before login stays valid after it (session fixation). |
| 8 | `identity-service/…/member/` (`MemberRepository`, `MemberService`) | `SEC-13`: `Member` is the only entity **without** `@TenantId` (💡 see L2), so it's filtered by hand. Read the Javadoc — those are the rules. |
| 9 | `care-service/…/common/CareSecurityChainConfiguration` + `record/administrative` and `record/clinical` | The central business rule: the clinical side is reserved to `PRACTITIONER` (not `CLINIC_ADMIN`), and **one DTO per route**. |
| 10 | `care-service/…/accesslog/` | Every successful clinical read leaves a trace, in an **append-only** table (trigger). 💡 The log is written by a separate bean rather than by the aspect itself: `@Transactional` on a method an aspect calls **on itself** is silently ignored — self-invocation bypasses the Spring proxy that carries the transaction. |
| 11 | `*/src/main/resources/db/changelog/changes/` | The database constraints (**L4**): `clinic_id NOT NULL`, composite FKs `(id, clinic_id)`, `CHECK` against the impossible clinic. |
| 12 | `platform/api-gateway/…/StatelessPassthroughConfiguration` | The bug that replayed the last logged-in user's session for everyone. 💡 Apache HttpClient 5 enables a cookie store **shared by the whole HTTP client** by default — a gateway proxying everyone through a single client picked up the last connected user's session cookie and replayed it for everyone else, with nothing logged. |
| 13 | `client/src/app/auth/` | Login, route guard, CSRF. The guard protects the **experience**, not the data. 💡 CSRF here means double-cookie: an `XSRF-TOKEN` cookie readable by JS (unlike the session cookie, which is `HttpOnly`) that the SPA copies into the `X-XSRF-TOKEN` header — a third-party site can't read it, so it can't copy it either. |

The rest (`outbox/`, `InternalAssignmentsController`, `seed/`) can be read as needed.

## The tests: where the proof is

- **Reference data**: `shared/src/test/…/fixtures/SecurityFixtures` — `alice`, `bob`, `carol`,
  `dan`, and above all `erin` (PRACTITIONER in A, RECEPTIONIST in B) and patient `P1`, present in
  both clinics.
- **The matrix, executable**: `src/test/resources/matrice.csv` in each service — F2 and INV-1 read
  it. **It precedes the code.**
- **The INV-1…6 inventories**: `shared/…/fixtures/InventoryRules` (written once), wired up by
  `*InventoryTest`. `InventoryRulesTest` deliberately makes them fail, to prove they actually see
  something. 💡 Trap hit while writing INV-3: ArchUnit's `isAnnotatedWith` does **not** follow
  meta-annotations, so `@NativeQuery` (which *is* a `@Query(nativeQuery = true)` under another name)
  went unnoticed until the rule looked for it explicitly, by its own name.
- **One criterion → one test**: see `tests.md`.

## To change something

| I want to… | I touch, in this order |
|---|---|
| Add a route | `authorization-matrix.md` → `matrice.csv` → the service's chain. Otherwise INV-1 fails. |
| Add an entity with `clinicId` | `@TenantId` on the field (otherwise INV-2), constraints in the changelog. |
| Write native SQL | Forbidden (INV-3) without `@TenantOverride` + a cross test. |
| Read identity in a controller | Inject `CurrentUser`. Never `Authentication` nor `HttpSession` (INV-6). |

## Running it

- Full build: `mvn clean verify` in `server/` — **Docker required**, and **JDK 25**: if
  `JAVA_HOME` points at another JDK, Maven fails with "release version 25 not supported".
- Locally: `dev` profile on `identity-service`, then `_dev/session-smoke-test.ps1` to get a test
  session (accounts in `identity-service-dev.yml`).
