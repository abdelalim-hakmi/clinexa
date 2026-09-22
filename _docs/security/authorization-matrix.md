# Authorization matrix — security foundation (J3)

> Single source of truth for route authorization. Written **before** any Spring Security
> configuration, and updated **before** adding an endpoint — not after.
>
> Design reference: `Clinexa-vault/Security/MVP/03-rbac-et-matrice-autorisation.md` §7, and
> `design-v1/3-LLD/21-LLD-contrats-api.md` §7–8 for the exact paths.

## Why this file exists before the code

A matrix written **after** the configuration no longer verifies anything: it just describes what
the code already does. Written **before**, it's the specification the code and tests are measured
against.

It's also the data source for two tests, which is what keeps it from becoming dead documentation:

| Test | What it reads | What it catches |
|---|---|---|
| **F2** — `IdentityAuthorizationMatrixTest`, `CareAuthorizationMatrixTest` | each service's `src/test/resources/matrice.csv` | A matrix cell the code doesn't honor, ✅ **as well as** ❌ |
| **INV-1** — `IdentityInventoryTest`, `CareInventoryTest` | the endpoints actually exposed | A route added without updating this file |

Each service keeps **its own** matrix (`SEC-11`: the matrix doesn't move up into `shared`). The CSV
is the mechanical transcription of the tables below; the two are meant to be read together.

## The choice that makes everything else testable: separate by route, not by field

| ❌ By field | ✅ By route |
|---|---|
| One DTO, clinical fields hidden at runtime based on role | Two endpoints, two DTOs, two access rules |
| A newly added field is exposed **by default** | A newly added field goes into **one** already-scoped DTO |
| Tested field by field, forever | Tested route by route, once |

This is invariant **I5**, and it only costs one extra DTO. At the foundation, it's backed by a
**physical** separation too: `patient_record` and `clinical_record` are two tables (LLD 20 §4.1), so the
administrative use case has no path at all to the clinical one, even by mapping mistake.

## The roles

**No global role at the foundation.** The only candidate was `PATIENT`, and `SEC-02` defers the
patient portal: no patient account, no patient session, so no global role — and **no
`/api/v1/me/**` route at all** (criterion 11, negative test).

| Clinic role | Carried by | Scope |
|---|---|---|
| `RECEPTIONIST` | the `(account, clinic)` assignment | front desk, calendar, administrative side of the record |
| `PRACTITIONER` | the `(account, clinic)` assignment | calendar, administrative **and** clinical sides |
| `CLINIC_ADMIN` | the `(account, clinic)` assignment | members, settings, billing, management |

Deferred roles, noted here for the record and **not implemented**: `PATIENT`, `PLATFORM_ADMIN`,
`LOCUM`, `NURSE`. The model will accommodate them without a migration, since the role is
already carried by the assignment.

**Roles are disjoint, never hierarchical** (I6, `SEC-06`). A legitimate combination of rights — a
manager who also staffs the front desk — is modeled as **multiple assignments**, visible in the
database and revocable separately. INV-4 checks that no `RoleHierarchy` bean exists.

---

## 1. Public routes — no authentication required

| Route | Verb | Service | What it must never expose |
|---|---|---|---|
| `/api/v1/auth/login` | `POST` | identity | Whether the email exists or not. A failure answers `401 AUTH_NOT_AUTHENTICATED`, never "unknown account" — otherwise the route becomes an account-existence oracle. |
| `/api/v1/auth/csrf` | `GET` | identity | The CSRF token, in the `XSRF-TOKEN` cookie **and** in the body. It isn't an authentication secret: it proves the request comes from a page able to read the cookie — which another site cannot do. Nothing else is exposed. |
| `/actuator/health` | `GET` | identity, care | Non-detailed version (`01-durcissement-applicatif` §5). |

> **Why a dedicated route for the CSRF token.** A browser only sends the `X-XSRF-TOKEN` header if it
> has **already seen** a cookie of the same name. Someone landing directly on the login screen
> hasn't made any request yet: their first `POST` would go out with no token and be refused by a
> `403` that explains nothing — trap #1 of the mechanism. This route is a deterministic bootstrap
> point, instead of hoping an earlier response happened to carry the cookie.
>
> The body carries the **raw** token — the same one as the cookie, not the masked form an HTML form
> posts in `_csrf`. It's the one the chain compares against the header; serving the other one would
> give a token that's always refused. `CsrfBootstrapTest` locks in this equality.

`CMP-PUB`'s public routes (directory, slots, booking, OTP) belong to V3: none of them exist at the
foundation, and the last `denyAll()` line refuses them until then.

## 2. Internal route — outside the gateway

| Route | Verb | Service | Rule |
|---|---|---|---|
| `/internal/accounts/{accountId}/assignments` | `GET` | identity | **Never routed by the gateway** (LLD 21 §8; `configurations/api-gateway.yml` doesn't declare it). Reachable on the internal network only. Service authentication (mTLS) is **set aside at the foundation** — trusted local Docker network — and this debt **must be reopened before production** (`SEC-08`, `01-durcissement-applicatif` §6). |

It only exposes clinic ids and roles: no name, no email, no status.

## 3. Authenticated routes

Reading: ✅ = the role passes the **route filter**. The resource is then still subject to the tenant
filter (L1/L2) **and** ownership rules. All three filters stack.

| Route | Verb | Service | `RECEPTIONIST` | `PRACTITIONER` | `CLINIC_ADMIN` |
|---|---|---|:---:|:---:|:---:|
| `/api/v1/auth/logout` | `POST` | identity | ✅ | ✅ | ✅ |
| `/api/v1/me` | `GET` | identity | ✅ | ✅ | ✅ |
| `/api/v1/clinics/{c}/members` | `GET` | identity | ❌ | ❌ | ✅ |
| `/api/v1/clinics/{c}/members/{id}` | `GET` | identity | ❌ | ❌ | ✅ |
| `/api/v1/clinics/{c}/records/{id}/administrative` | `GET` | care | ✅ | ✅ | ❌ |
| **`/api/v1/clinics/{c}/records/{id}/clinical`** | `GET` | care | **❌** | ✅ | **❌** |
| `/actuator/**` (other than `/health`) | `*` | identity, care | ❌ | ❌ | ❌ |
| **Every other route** — including `/api/v1/me/**` | `*` | all | ❌ | ❌ | ❌ |

`/api/v1/auth/logout` and `/api/v1/me` are ✅ across all three columns because they don't
depend on any clinic: the rule is "authenticated", not "a given role". They stay in the table rather
than off to the side, so that no authenticated route escapes review.

### What the foundation doesn't expose, and why

| Missing | Reason |
|---|---|
| Any write route on `care` | `SEC-14`: the skeleton carries 2 reads, 0 writes. Any extension before V1 requires an ADR. |
| The `/api/v1/clinics/{c}/records` collection | Same reason. Re-check ③ from `03-rbac` §7.3 — "the collection only returns the administrative projection" — is therefore **moot at the foundation** and becomes applicable again once the collection is created, in V1. |
| Write routes on `members` (assignment, role change, revocation) | The service methods exist and are tested (guide 8.5, events published starting in V0); the routes are born with the feature that needs them. |
| `/api/v1/me/**` | `SEC-02`: no patient account or session at the foundation. Refused by `denyAll()`, proved by a negative test (criterion 11). |
| Calendar, appointments, slots, consultations, prescriptions, invoices, reports | `scheduling`, `billing`: V1 and V2 (`DA-01`). Their rows will join this table with their service. |

---

## 4. The four mandatory re-reads

**① The clinical side is reserved to `PRACTITIONER`**, even against `CLINIC_ADMIN`.
→ `…/clinical` line: a single ✅ column. Criterion 8.

**② `CLINIC_ADMIN` has access to neither the calendar nor patient records**, not even their
administrative side. Its scope is members / settings / billing / management. Roles are disjoint
(I6); a manager who also staffs the front desk carries **two assignments**.
→ `…/administrative` line: `CLINIC_ADMIN` ❌.

**③ The `/records` collection only returns the administrative projection** — moot at the
foundation: the collection doesn't exist (`SEC-14`). To be reapplied when it's created.

**④ The last line is `anyRequest().denyAll()`** (I1) — and it's written in no service:
`SecurityChainBuilder` adds it itself, so no service can ship without it.

---

## 5. What the matrix can't express: ownership rules

A route × verb × role matrix says nothing about "**this specific** resource". These rules belong to
method-level security (`@PreAuthorize`, `@EnableMethodSecurity` — enabled on both services).

| Rule | Scope | State at the foundation |
|---|---|---|
| A practitioner only edits **their own** calendar — except `CLINIC_ADMIN` | `Appointment`, `Slot` | V1 — `scheduling-service` doesn't exist |
| A prescription is only signed by **its** author | `Prescription` | V1 |
| A settled invoice can no longer be modified | `Invoice` | V2 |
| A patient only acts on **their own** appointments | `/me/**` | Deferred with the portal (`SEC-02`) |

**No ownership rule applies at the foundation**: the only two business routes are reads of a
resource with no "author", and the tenant already fully bounds them. This isn't an oversight — it's
a consequence of the `SEC-14` scope, and it's what criterion 11 (negative test on `/me/**`) checks
on the foundation's side.

**Writing rule going forward**: the check applies to the resource **loaded from the database**,
never to an id received in the request. `@PreAuthorize` on a URL parameter checks an *intent*; after
loading, it checks a *fact*.

---

## 6. The three refusals, and why they must not be confused

| Situation | Response | `code` | Who refuses |
|---|---|---|---|
| No session, or an invalidated session | `401` | `AUTH_NOT_AUTHENTICATED` | the chain |
| `alice` (clinic A) on `/clinics/`**`B`**`/…` | `403` | `AUTH_CLINIC_NOT_ASSIGNED` | **L1** — B is not among her assignments |
| `bob` (RECEPTIONIST) on `…/clinical` | `403` | `AUTH_ROLE_INSUFFICIENT` | the matrix |
| A tenant operation with no clinic context | `403` | `AUTH_CLINIC_CONTEXT_MISSING` | `TenantContext.requireClinicId()` |
| `identity-service` unreachable | `403` | `AUTH_ASSIGNMENTS_UNAVAILABLE` | `SEC-10`'s fail-closed |
| `alice` (clinic A) on `/clinics/`**`A`**`/records/{idOfB}`| **`404`** | — | **L2** — `@TenantId` finds nothing |

The last two lines of criterion 5 are **the easiest point to get wrong**. In `403`, the path itself
isn't legitimate and saying so reveals nothing: clinic B is a public entity. In `404`, the path is
legitimate and answering `403` would reveal that this identifier **exists** somewhere. "You're not
allowed in here" and "there's nothing here **for you**" are two different statements, and an
implementation that answers `403` in both cases passes a test that only checks the first one.

Each refusal's body is a *Problem Details* (RFC 7807, LLD 21 §3): it never says whether the resource
exists, and never contains data from another clinic.

---

## 7. CSV format

`src/test/resources/matrice.csv`, one per service, `;`-separated:

```
route;verb;role;allowed
```

- `route` — the URL pattern with `{}` in place of each variable, to compare against Spring's
  patterns without depending on variable names.
- `role` — `RECEPTIONIST`, `PRACTITIONER`, `CLINIC_ADMIN`, or `ANONYMOUS` for a request with no
  session.
- `allowed` — `true`: the role passes the route filter (the response can still end up `404`).
  `false`: the response is `401` (anonymous) or `403`.

Every ✅ line **and** every ❌ line is executed: checking that a practitioner reaches the record
proves nothing about isolation; checking that the receptionist can't, does.
