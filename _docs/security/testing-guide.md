# Testing guide — how to check that everything actually works

> This is a **practical, run-it-yourself** guide: for every kind of test this project can have, what
> it is, how to run it here, and why it exists. `tests.md` documents *what the security suite proves*
> (F1–F6, INV-1–6, the thirteen criteria); this document is broader — every test type in the repo,
> security or not, plus the manual checks nothing automates yet. Nothing here changes code; every
> command is read-only or spins up disposable containers.

## The one command that proves the most

```bash
cd server
mvn clean verify
```

This is **exactly what CI runs** (`.github/workflows/ci.yml`). It builds every module, runs all
~113 backend tests, and fails the build if fewer than 100 tests actually ran (a guard against a
silently broken test configuration) or if any test carries `@Disabled`/`@Ignore`. Needs **Docker
running** (tests start real Postgres/Redis/Kafka containers) and **JDK 25** on `JAVA_HOME` — a lower
version fails with `release version 25 not supported`.

```bash
cd client
yarn test --watch=false
```

Client-side equivalent: 7 tests, no backend required (the HTTP layer is mocked).

If both pass, you have machine-checked the whole backend security foundation, the outbox, the
tenant isolation, the config schemas, and the login flow's client-side logic. What they **can't**
tell you: whether the services actually start together, whether Eureka/Config Server wiring holds,
and whether a real browser talking to a real gateway behaves the same way — that's what the later
sections are for.

## Catalogue — every test type in this repo

| Type | Tool | Proves | Where | Run it |
|---|---|---|---|---|
| Unit | plain JUnit 5 | One class's logic, no Spring context | `shared` (3 classes) | `mvn -pl shared test` |
| Architecture (static) | ArchUnit | A rule about the **code**, not a behavior | `InventoryRules` + `*InventoryTest` | part of `mvn test` |
| Meta-test ("proves the test bites") | JUnit, inverted assertion | An inventory rule isn't a dead rule | `InventoryIntentionallyFailsTest`, `InventoryRulesTest` | part of `mvn test` |
| Integration / component | `@SpringBootTest` + `MockMvc` + Testcontainers | A slice of real behavior, against real Postgres/Redis/Kafka | most of `*Test.java` in both services | `mvn test` |
| Contract / matrix-driven | JUnit `@ParameterizedTest` reading a CSV | Every route × role cell, ✅ and ❌ | `*AuthorizationMatrixTest` | part of `mvn test` |
| Frontend unit | Vitest + Angular `TestBed` + `HttpClientTesting` | Component/service logic, HTTP mocked | `client/src/app/app.spec.ts` | `yarn test` |
| End-to-end smoke (scripted) | PowerShell + `Invoke-RestMethod` | The **real** stack, over the network, no mocks anywhere | `_dev/*.ps1` | `.\_dev\session-smoke-test.ps1` |
| Manual API testing | curl / Postman / Insomnia | Whatever an automated test doesn't cover yet, or a one-off question | — (no saved collection) | see below |
| CI | GitHub Actions | The build is reproducible outside your machine, and blocks a red merge | `.github/workflows/ci.yml` | push a branch, or read it locally |
| Manual security drill | reading a log | Building the mental model, not a pass/fail | guide step 5.9 | see below |

Not present, and worth knowing that explicitly: no saved Postman/Insomnia collection, no OpenAPI/
Swagger spec, no browser E2E tool (Cypress/Playwright — `client/README.md` says so outright), no
load/performance test, no dependency-vulnerability scan, no mutation-testing tool (PIT). None of
these are missing by oversight at this stage — see **Gaps** at the end for which would actually pay
off next.

---

## 1 · Unit tests — plain JUnit, no Spring context

**What.** A test that instantiates a class directly (`new TenantContext()`) and asserts on it. No
`@SpringBootTest`, no container, runs in milliseconds.

**Where.**

| Class | What it checks |
|---|---|
| `shared/…/tenant/TenantContextTest` | The `ThreadLocal` semantics: `set`/`clear`/`requireClinicId`/`runIn`, and that a leaked value doesn't survive `clear()`. |
| `shared/…/identity/SessionCurrentUserResolverTest` | The resolver reads `SecurityContextHolder` correctly and memoizes per request. |
| `shared/…/web/SecurityChainBuilderTest` | The CSRF cookie/header names match Angular's defaults, and the cookie is not `HttpOnly`. |
| `identity-service/…/authentication/PasswordHashTest` | The BCrypt hash committed in the dev seed actually matches `Clinexa!2026` — catches a stale hash after a password change. |

**How.** `mvn -pl shared test` (or target any class: `mvn -pl shared -Dtest=TenantContextTest test`).

**Why this category matters on its own.** These are the tests you re-run in a tight loop while
touching `shared` — no Docker, no Spring context startup, feedback in under a second. If one of
them breaks, don't bother running the integration suite first: fix this one, it's upstream of
everything else.

💡 This project doesn't use Maven's classic unit/integration split (`surefire` for `*Test`,
`failsafe` for `*IT` in a later `verify` phase). Every test — unit or not — is a `*Test.java` file
run by `surefire` in the `test` phase. The unit/integration distinction here is about *what a class
does* (does it start a Spring context and a container, or not), not about *which Maven plugin runs
it* or *which build phase it runs in*.

## 2 · Architecture tests — ArchUnit

**What.** A test that inspects compiled **bytecode**, not behavior: "does any class depend on
`RoleHierarchy`?", "does every `@Entity` field named `clinicId` carry `@TenantId`?". It fails a
*shape* of the code, and it fails **at build time**, before anyone runs anything.

**Why a separate category from integration tests.** A behavioral test can only prove a rule holds
*for the inputs it tried*. An ArchUnit rule proves a rule holds for **every class that will ever
exist** matching its pattern — including one written by someone who never read the security docs.
That's why `tests.md` calls the inventory the most cost-effective test in the whole suite.

**Where.** The six rules live once in `shared/…/fixtures/InventoryRules` (`INV-2` through `INV-6`;
`INV-1` is the matrix check, category 4 below) and are wired into each service by
`IdentityInventoryTest` / `CareInventoryTest`.

| Rule | Catches |
|---|---|
| INV-2 | A `clinicId` field on an `@Entity` without `@TenantId` — or a second name slipped into the allowlist |
| INV-3 | Native SQL / bulk JPQL without `@TenantOverride`, **and** direct `EntityManager.createNativeQuery`/JDBC use |
| INV-4 | A `RoleHierarchy` bean, in the code or in the running context |
| INV-5 | An administrative DTO depending on a clinical type |
| INV-6 | `Authentication`/`SecurityContextHolder`/`Jwt`/`HttpSession` read outside the authentication mechanism |

**How.** Runs inside `mvn test` like any other test; to isolate one:
`mvn -pl services/care-service -Dtest=CareInventoryTest test`.

## 3 · Meta-tests — proving a test can actually fail

**What.** A test whose assertion is **inverted**: it fails if a rule *stops* catching a bad sample.
This answers a question ordinary tests can't: "has anyone ever seen this rule turn red?" A rule
nobody has seen fail is a rule nobody knows still works — it could have been silently broken by a
refactor and nobody would notice, because it's been green the whole time for the wrong reason.

**Where.**

- `InventoryIntentionallyFailsTest` (identity-service) — adds an undeclared debug endpoint in its
  own test-only Spring context, and asserts that INV-1 reports it **and** that `denyAll()` actually
  refuses it at runtime (not just flagged, refused).
- `InventoryRulesTest` (shared) — feeds `InventoryRules.pasDeRequeteNativeNonDeclaree` /
  `pasDeSqlNatifDirect` a deliberately bad sample (a repository using `@NativeQuery`, a service
  calling `createNativeQuery`) and a deliberately clean one, and checks the rule reacts to both.

**Why it's worth knowing as its own category.** If you ever add or change an ArchUnit rule, write
one of these alongside it. Anyone can write a rule that happens to pass on the current codebase —
the only way to know it actually *checks* something is to aim it at code you know is wrong and
watch it fail.

## 4 · Integration / component tests — `@SpringBootTest` + `MockMvc` + Testcontainers

**What.** The bulk of the suite (~90 of the ~113 backend tests). Boots a real Spring application
context, talks to it through `MockMvc` (an in-process HTTP client — no socket, but the full filter
chain runs), and persists to **real** Postgres/Redis/Kafka in Testcontainers — not H2, not mocks.

💡 **Why real containers and not mocks.** Mocking `CurrentTenantIdentifierResolver` in a test proves
the mock returns what you told it to, not that Hibernate's own `@TenantId` filter works. `tests.md`
calls this one of the three classic traps in this codebase. The composite foreign keys, the
append-only trigger, the `@TenantId` filter — none of those exist in a mock; they're Postgres's and
Hibernate's own mechanisms, and only a real database can refuse what they're supposed to refuse.

**Where — by what they exercise:**

| Family | Classes | Proves |
|---|---|---|
| Authentication | `AuthenticationTest`, `CsrfBootstrapTest` | 401 with/without session, session-fixation protection, CSRF bootstrap |
| Tenant isolation | `TenantIsolationTest` (care), `MemberSec13Test` (identity) | A's account reaches nothing of B's, entity by entity |
| Field leak | `FieldLeakTest` | The **serialized JSON**, not the DTO's Java type, carries no clinical key for a non-practitioner |
| Access log | `AccessLogTest` | A clinical read is logged; a refused/404 one isn't; the log is append-only in the database |
| Fail-closed on outage | `AssignmentsUnavailableTest` | `identity-service` unreachable → `403`, never an unfiltered response |
| Fail-closed off-request | `ConsumerWithoutTenantTest` | A Kafka event with no `clinicId` is refused by a consumer, on a thread that never saw an HTTP request |
| Outbox | `OutboxRelayTest` | Events are published transactionally, and the relay's native-SQL override only ever drains, never leaks across tenants |
| Schema/entities | `SchemaAndEntitiesTest` | Liquibase changelog and JPA entities agree (`ddl-auto=validate`); `@TenantId` filtering and cache config are real |
| Error handling | `UnmaskedErrorTest` | The container's own `/error` dispatch isn't swallowed by `denyAll()` and turned into a false `403` |
| Gateway | `SessionLeakTest` | The gateway's HTTP client doesn't share a cookie store across callers |

**How.**

```bash
mvn -pl services/identity-service -am test              # one service + what it depends on
mvn -pl services/care-service -Dtest=TenantIsolationTest test   # one class
```

💡 **Containers start once per build, not once per class.** Each service's `*Containers` support
class (`IdentityContainers`, `CareContainers`) is shared across every test in that module via a
static/singleton container pattern — starting Postgres/Redis/Kafka fresh per test class would make
the suite unusably slow, and `tests.md` is explicit that a slow security suite is a suite people
eventually stop running.

## 5 · Contract / matrix-driven tests — one CSV, one parameterized test

**What.** Rather than one hand-written test per endpoint (which forgets things — usually the ❌
cases, which are exactly the ones that prove isolation), each service reads its own
`src/test/resources/matrice.csv` and runs **one test execution per row**: route, verb, role,
allowed-or-not.

**Why this earns its own category.** It's the same mechanism as INV-1 in reverse: the matrix *is*
the specification (`_docs/security/authorization-matrix.md` in prose, `matrice.csv` in machine
form), written **before** the `SecurityFilterChain` config. `IdentityAuthorizationMatrixTest` and
`CareAuthorizationMatrixTest` don't test "does the code do the right thing" so much as "does the
code match the spec" — and `INV-1` (category 2) separately checks nothing in the running app is
missing from that CSV, in both directions.

**How.** Runs inside `mvn test`. To see every row execute as its own named test case, run with a
verbose reporter or open the Surefire XML report:
`server/services/identity-service/target/surefire-reports/*IdentityAuthorizationMatrixTest.txt`.

## 6 · Frontend unit tests — Vitest + Angular `TestBed`

**What.** `client/src/app/app.spec.ts` — Angular's `TestBed` with `provideHttpClientTesting()`, so
`AuthService` runs for real but every HTTP call is intercepted and answered by hand
(`http.expectOne(...).flush(...)`). No real backend, no browser navigation — this is a unit test of
TypeScript logic, not a UI test.

**What it actually proves**, beyond "the component renders": that a `401` on `/api/v1/me` is read
as "not logged in" rather than an error; that login always fetches the CSRF token first; that a
failed CSRF bootstrap doesn't block the login attempt; that the server's error message is shown
verbatim, without the client trying to reconstruct "does this account exist"; that logout is
treated as done even if the server call fails.

**How.**

```bash
cd client
yarn test              # watch mode, for active development
yarn test --watch=false   # single run, what CI does
```

**What's not covered yet.** `auth.guard.ts` and the `login` component (`login.ts`/`.html`) have no
dedicated spec file — only exercised indirectly through `App`'s own test. Real UI behavior (does the
form actually submit, does the guard actually redirect in a browser) isn't tested at all — see
Gaps.

## 7 · End-to-end smoke tests — the real stack, scripted

**What.** PowerShell scripts under `_dev/` that hit **running** services over real HTTP/TCP, with no
mocks and nothing in-process. This is the only category that proves the services actually start,
find each other through Eureka, and share Redis for real — nothing above this line touches more
than one JVM at a time.

**Where, and what each proves:**

| Script | Proves |
|---|---|
| `postgres-smoke-test.ps1` | Container healthy, `INSERT`/`SELECT` round-trip |
| `redis-smoke-test.ps1` | `PING`, `SET`/`GET` round-trip |
| `kafka-smoke-test.ps1` | Broker up, topic create, produce/consume round-trip |
| `zipkin-smoke-test.ps1` | UI reachable, a span can be posted and read back |
| `session-smoke-test.ps1` | The one that matters most here: CSRF token → login → `/api/v1/me` → **the same session cookie accepted by `care-service`** (proves Redis sharing for real) → logout → the cookie is worthless afterward |

**How.**

```powershell
docker compose up -d
# start config-server, discovery-server, identity-service (-Dspring.profiles.active=dev), care-service, api-gateway, in that order
.\_dev\session-smoke-test.ps1
.\_dev\session-smoke-test.ps1 -Utilisateur alice@clinic-a.ma   # any dev account
.\_dev\session-smoke-test.ps1 -BaseUrl http://localhost:9000   # through the gateway instead of direct
```

**Why this category is not redundant with the `MockMvc` tests.** `MockMvc` never opens a socket and
never starts a second JVM — it can't catch a wrong port in `configurations/*.yml`, a service that
registered under the wrong Eureka name, or (the actual historical bug this project hit) a gateway
whose HTTP client shares state across callers only when *two real requests* interleave. Everything
in this section only fails when something outside any one service's own test suite is wrong.

## 8 · Manual API testing — curl and Postman

**What.** There is **no saved Postman/Insomnia collection** in this repo. For a one-off question
("does this specific response actually look right") or exploring an endpoint the automated tests
don't cover, use curl or a scratch Postman collection built from the sequence below.

💡 **Why this is trickier than "just call the endpoint".** The API is cookie-session + CSRF, so a
tool has to (1) keep a cookie jar across requests and (2) copy the CSRF token from the bootstrap
response into a header on every mutating request. Skipping either gets you an unexplained `403`,
same trap `authentication.md` calls the #1 pitfall of this mechanism.

**curl, keeping a cookie jar (`-c`/`-b`), against `identity-service` directly:**

```bash
# 1. Get a CSRF token — also plants the XSRF-TOKEN cookie
curl -s -c cookies.txt http://localhost:8100/api/v1/auth/csrf
# -> {"headerName":"X-XSRF-TOKEN","token":"<TOKEN>"}

# 2. Log in, replaying the cookie jar and the token
curl -s -b cookies.txt -c cookies.txt \
  -H "X-XSRF-TOKEN: <TOKEN>" -H "Content-Type: application/json" \
  -d '{"email":"alice@clinic-a.ma","password":"Clinexa!2026"}' \
  http://localhost:8100/api/v1/auth/login

# 3. Ask who you are — no token needed, GET is not protected by CSRF
curl -s -b cookies.txt http://localhost:8100/api/v1/me

# 4. Same session, different service (proves Redis sharing — same idea as session-smoke-test.ps1)
curl -s -b cookies.txt http://localhost:8101/api/v1/clinics/0193a000-0000-7000-8000-00000000000a/records/<id>/administrative

# 5. Log out
curl -s -b cookies.txt -c cookies.txt -H "X-XSRF-TOKEN: <TOKEN>" \
  -X POST http://localhost:8100/api/v1/auth/logout
```

Dev accounts and the shared password are in `identity-service-dev.yml`
(`alice@clinic-a.ma` / `bob@clinic-a.ma` / `carol@clinic-a.ma` / `dan@clinic-b.ma` /
`erin@clinexa.ma`, password `Clinexa!2026` for all). Clinic ids are the fixed UUIDs in
`SecurityFixtures` (clinic A ends `…00000a`, clinic B `…00000b`).

**Building a Postman collection from this, if you want a reusable one:** Postman → Import → paste
one of the curl commands above → repeat for each step → group them into a collection →
enable "Automatically follow redirects" and let Postman's own cookie jar persist across requests in
the same collection run (it does, by default, within a run) → for step 2, add a **Tests** script on
step 1 that captures the token into a collection variable
(`pm.collectionVariables.set("csrf", pm.response.json().token)`), then reference `{{csrf}}` as the
`X-XSRF-TOKEN` header on every POST. That turns the manual sequence above into a one-click replay —
worth doing once if you'll be poking the API by hand often; see **Gaps** if it's worth committing to
the repo.

**Through the gateway instead of a service directly:** same recipe, base URL `http://localhost:9000`
— confirms routing and same-origin behavior, not just the service's own logic.

## 9 · CI — checking the build is real, not just green

**What.** `.github/workflows/ci.yml` runs the exact commands above on a clean GitHub-hosted runner:
`mvn -B -ntp clean verify` for the backend, `yarn test --watch=false` + `yarn build` for the client.
Two extra guards run after the backend build:

- **Test count check** — parses every `TEST-*.xml` Surefire report and fails if fewer than 100 tests
  ran in total. Catches the "green CI that tested nothing" trap (a module silently dropped from the
  reactor, a broken Surefire include pattern).
- **`@Disabled` guard** — greps every `src/test/**/*.java` in the reactor for `@Disabled`/`@Ignore`
  and fails the build if it finds one, anywhere — not just in security-named packages (an earlier,
  narrower version of this check missed the gateway's session-leak test and the outbox override
  test, precisely because their names don't contain "security").

**How to verify it locally before pushing**, without waiting for GitHub:

```bash
cd server
mvn -B -ntp clean verify
# then, the same two checks CI runs:
find . -name 'TEST-*.xml' -path '*/surefire-reports/*' -print0 \
  | xargs -0 grep -ho 'tests="[0-9]*"' | grep -o '[0-9]*' | paste -sd+ - | bc
grep -rnE --include='*.java' --exclude-dir=target '@(Disabled|Ignore)\b' . | grep '/src/test/'
```

The first command should print a number ≥ 100; the second should print nothing.

**The one thing CI cannot check by running.** Criterion 13 also requires the status check to be
**required** on `develop`/`main` (GitHub → Settings → Branches). That's a repository setting, not
something a test can assert — verify it by looking, not by running anything.

## 10 · Manual security drill — guide 5.9

**What.** Not a test at all: turning `logging.level.org.springframework.security=DEBUG` on for one
real request and reading the trace. `identity-service-dev.yml` leaves it at `INFO` by default
because at `DEBUG` it's too dense for daily use — but doing it **once** is how the difference between
authentication (F1) and authorization (F2/matrix) stops being abstract.

**How.**

```yaml
# temporarily, in identity-service-dev.yml or as a JVM arg
logging.level.org.springframework.security=DEBUG
```

Then log in through the smoke test or curl and read the console: you'll see the filter chain
evaluated in order, `TenantFilter` swap in a scoped `Authentication`, and `AuthorizationFilter`
consult the matrix — the exact sequence `reading-the-code.md` describes in prose, now visible in a
real log.

---

## "I want to be sure everything works, right now" — the recipe

1. `cd server && mvn clean verify` — backend, ~113 tests, needs Docker + JDK 25.
2. `cd client && yarn test --watch=false` — frontend, ~10 tests, no backend needed.
3. `docker compose up -d` from the repo root (generate `KAFKA_CLUSTER_ID` once if `.env` doesn't
   have it yet — see root `README.md`).
4. Start `config-server` → `discovery-server` (wait for it to answer) → `identity-service` with
   `-Dspring.profiles.active=dev` → `care-service` → `api-gateway`, in that order.
5. `.\_dev\session-smoke-test.ps1` — proves the real stack, real session sharing, end to end.
6. Optional, only if you touched something the automated suite might not cover: a manual curl/Postman
   pass through the gateway (section 8).
7. `git status` on `_dev/*.log` and clean them if you're about to commit (they're git-ignored, but
   worth knowing they're there).

If 1, 2 and 5 are all green, the security foundation, the config wiring, and the login flow are all
verified — that's the highest-value 10 minutes available in this repo.

## Symptom → where to look

| You see… | Check first |
|---|---|
| A `mvn test` failure in `shared` | Section 1 (unit) — fix here before anything downstream |
| An `Inventory*Test` failure | Section 2 — read the assertion message, it names the exact rule and violation |
| A `*Test` failure only in CI, not locally | `JAVA_HOME` / Docker daemon differences, or a container port clash — rerun `mvn clean verify` locally with the exact CI command |
| `401`/`403` you didn't expect from curl/Postman | Section 8 — check the CSRF token was copied into the header, and that the cookie jar carried over |
| Services won't talk to each other locally | Section 7 — run the infra smoke tests first (`postgres`/`redis`/`kafka`) to rule out Docker, then `session-smoke-test.ps1` to rule out app wiring |
| "Is this endpoint even in the matrix?" | Open `matrice.csv` for that service, or run `IdentityInventoryTest`/`CareInventoryTest` — INV-1 fails loudly if it isn't |
| CI green but you don't trust it | Section 9 — run the two extra guard commands locally |

## Gaps — feasible, not built

Listed so a decision to add one is deliberate, not accidental:

- **A saved Postman/Insomnia collection** — the curl sequence in section 8 is copy-pasteable into
  one in a few minutes; nothing currently keeps it in the repo for reuse.
- **Browser E2E (Playwright/Cypress)** — would be the only thing that actually drives `login.ts`
  through a real page and confirms `auth.guard.ts` redirects correctly; today that path has zero
  test coverage of any kind. `client/README.md` already flags this as absent.
- **`auth.guard.ts` and `login` component unit tests** — both are currently exercised only
  indirectly through `App`'s spec.
- **An OpenAPI/Swagger contract** — `matrice.csv` covers *who may call what*, not *what shape the
  response is*; nothing currently generates or checks a schema for `MeDto`, `MemberDto`, etc.
- **Dependency vulnerability scanning** (e.g. OWASP Dependency-Check, `npm audit` in CI) — not
  wired into `ci.yml` today.
- **Load/perf testing** — nothing checks the `SEC-10` assignments-call latency budget the guide's
  1.7 mentions as a switch trigger for the future event-driven option (O4).
