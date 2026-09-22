# Tracing — from a log line to the Zipkin waterfall in 3 steps

> Decision: `DA-14` in the vault (`design-v1/2-HLD/13-HLD-decisions-architecture.md` §17) —
> Micrometer Tracing, **Brave** bridge, exported to **Zipkin**. No Sleuth (gone since Boot 3), no OTel.

## Vocabulary

- **Trace** — the whole journey of one request, across every service it crosses.
- **Span** — one step of it (a server receiving, a client calling, a Kafka send).
- **`traceId`** — what ties the spans together. Same value in every service for the same request.

## The 3 steps

Prerequisites: the `zipkin` Docker profile is up (`http://localhost:9411`) and the services are
started in order: config-server → discovery-server → identity-service → care-service → api-gateway.

1. **Make a call through the gateway** — e.g. the session smoke test pointed at the gateway
   (`_dev/session-smoke-test.ps1 -BaseUrl http://localhost:9000`); by default it calls the services
   directly, which gives a trace without the gateway span.
2. **Copy the `traceId` from a log line.** Spring Boot puts it in every line logged during a
   request, between brackets after the thread name:
   `… [identity-service] [nio-8100-exec-1] [68f1c0e2a4b5d6e7f8a9b0c1d2e3f4a5-a4b5d6e7f8a9b0c1] …`
   — the first value is the `traceId`, the second the `spanId`. Empty brackets = logged outside a
   request (startup, scheduled job): no trace to find.
3. **Paste it in Zipkin** — search box at the top right of `http://localhost:9411`. The waterfall
   shows each span with its duration: the longest bar is where the time went; a red span is where it
   failed (useful for a `401`/`403` that crosses gateway + service).

One call through the gateway to a service = **one** trace: gateway `SERVER` → gateway `CLIENT` →
service `SERVER`, plus `care-service` → `identity-service` for the assignments call (`SEC-10`).
Two traces for one call means the context was not propagated — see the rules below.

## What is wired

| Where | How |
|---|---|
| `api-gateway`, `identity-service`, `care-service` (and the git-ignored `sandbox-service`) | `spring-boot-starter-zipkin` in the POM (brings `spring-boot-micrometer-tracing-brave`) |
| Sampling and Zipkin endpoint | `configurations/application.yml` in the Config Server, shared by all |
| Log correlation | Spring Boot's default `logging.pattern.correlation` — nothing to configure |

> [!WARNING]
> `management.tracing.sampling.probability: 1.0` traces **every** request. Dev only.

## Rules that keep one request = one trace

- HTTP calls between services go through the **injected** `@LoadBalanced RestClient.Builder`.
  A `new RestTemplate()` or a raw `java.net.http.HttpClient` is not instrumented: the trace breaks.
- Work handed to another thread (`@Async`, `CompletableFuture`, a custom pool) loses the context
  unless the executor is decorated — same rule as the tenant context (invariant I7).
- Never put personal or clinical data in a span tag: Zipkin is not access-controlled.

## Not done yet (vault `Personal-roadmap/06-tracing.md`)

- Kafka propagation checked end to end (the outbox relay uses `KafkaTemplate`, no consumer yet).
- A custom span (`@Observed`) with a business tag.
- Angular interceptor sending an `X-Request-Id`, logged by the backend.
