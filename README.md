# Clinexa

A microservices-based platform for managing appointments and practitioners. This is a monorepo combining a modern Angular frontend with a Spring Cloud backend.

## Project Overview

**Clinexa** consists of two independent parts:
- **Frontend**: Angular 22 SPA with Yarn package manager
- **Backend**: Spring Boot/Spring Cloud microservices architecture

See `_docs/clinexa-architecture.drawio.svg` for the system architecture diagram.

---

## Prerequisites (All)

Before setting up either the client or server, ensure you have:

| Tool | Version | Download |
|------|---------|----------|
| **Node.js** | 22.22.3+ (or 24.15.0+ / 26+) | [nodejs.org](https://nodejs.org) |
| **Java** | 25 | [openjdk.java.net](https://openjdk.java.net) |
| **Docker** & **Docker Compose** | Latest | [docker.com](https://docker.com) |

An IDE with Maven support (e.g. IntelliJ IDEA) is recommended for the backend — it bundles its own Maven, so a standalone Maven install isn't required.

---

## Quick Setup

### 1. Frontend Setup
See [client/README.md](client/README.md) for:
- Yarn & Node version verification
- Installing dependencies
- Running the dev server

### 2. Backend Setup
See [server/README.md](server/README.md) for:
- Building modules
- Running services

### 3. Docker & Environment Setup

```bash
cp .env.example .env
docker run --rm confluentinc/cp-kafka:7.6.0 kafka-storage random-uuid   # generate a Kafka Cluster ID
```
Paste the generated ID into `KAFKA_CLUSTER_ID` in `.env`, then start everything:
```bash
docker compose up -d
```

That's it — defaults in `.env.example` (incl. `clinexa`/`clinexa` dev credentials for Postgres, Mongo, and Redis) just work. Services are opt-in via `COMPOSE_PROFILES` in `.env` (default: `kafka,postgres,mongo,redis,zipkin`); `mail-dev` has no profile and always starts. Mongo Express and RedisInsight (web UIs for Mongo and Redis) share the `mongo`/`redis` profiles respectively — no separate profile needed. To run a subset:
```bash
docker compose --profile kafka --profile postgres up -d
```

#### Web UIs

| Service | URL | Login |
|---------|-----|-------|
| Kafka UI | http://localhost:8080 | — |
| Mongo Express | http://localhost:8081 | `MONGO_EXPRESS_USER` / `MONGO_EXPRESS_PASSWORD` |
| RedisInsight | http://localhost:5540 | Redis connection is preconfigured (`REDIS_PASSWORD`) |
| Zipkin | http://localhost:9411 | — |
| MailDev | http://localhost:1080 | — |

#### Smoke Tests

Validate a running stack end-to-end with the scripts in [`_dev/`](_dev/):

```powershell
.\_dev\kafka-smoke-test.ps1 -NonInteractive   # broker, topic, produce/consume round-trip (drop -NonInteractive to pause and eyeball Kafka UI)
.\_dev\postgres-smoke-test.ps1   # pg_isready, insert/select round-trip
.\_dev\mongo-smoke-test.ps1      # ping, insert/find round-trip
.\_dev\redis-smoke-test.ps1      # PING, SET/GET round-trip
.\_dev\zipkin-smoke-test.ps1     # UI up, span POST/GET round-trip
```
All check container health, cross-container network reachability, and accept override params — see each script for defaults.

---

## Repository Structure

```
clinexa/
├── README.md                          # You are here
├── .env.example                       # Environment template
├── docker-compose.yaml                # Services orchestration
├── postgres-initdb/
│   └── 01-create-databases.sh         # Postgres init script (run on first container startup)
├── _dev/
│   ├── docker-compose-fixes.md        # Dev-infra fix log
│   ├── kafka-smoke-test.ps1           # Kafka end-to-end smoke test
│   ├── postgres-smoke-test.ps1        # Postgres end-to-end smoke test
│   ├── mongo-smoke-test.ps1           # Mongo end-to-end smoke test
│   ├── redis-smoke-test.ps1           # Redis end-to-end smoke test
│   └── zipkin-smoke-test.ps1          # Zipkin end-to-end smoke test
├── _docs/
│   └── clinexa-architecture.drawio.svg # System architecture diagram
├── client/                            # Angular 22 SPA
│   ├── README.md
│   ├── package.json
│   ├── angular.json
│   └── src/
├── server/                            # Spring Boot microservices
│   ├── README.md
│   ├── pom.xml                        # Parent POM (multi-module)
│   ├── platform/
│   │   ├── config/                    # Config server
│   │   ├── discovery/                 # Eureka server
│   │   └── api-gateway/               # API Gateway
│   ├── services/
│   │   ├── rdv-service/               # Appointment service
│   │   ├── praticien-service/         # Practitioner service
│   │   └── ...
│   └── shared/                        # Shared library (DTOs, events, exceptions)
└── docs/                              # Additional documentation (future)
```

---

## Development Workflow

### Frontend Development
```bash
cd client
yarn install
yarn start    # Dev server at http://localhost:4200
```

### Backend Development
Open `server/` in an IDE with Maven support (e.g. IntelliJ IDEA) and run/build modules from there. See [server/README.md](server/README.md) for module structure and conventions.

### Docker Services
```bash
docker compose up -d    # Start profiles listed in COMPOSE_PROFILES (.env)
docker compose down     # Stop all services
```

---

## Documentation

- **[client/README.md](client/README.md)** — Client setup & commands
- **[server/README.md](server/README.md)** — Server setup & commands
- `CLAUDE.md` — Internal guidance for Claude Code (gitignored, local-only — not part of the shared repo)

---

## License

[Add license info here]
