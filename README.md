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

#### ⚠️ **IMPORTANT: Kafka Cluster ID Configuration**

Before running `docker-compose up`, you must generate and configure a **Kafka Cluster ID**:

```bash
# Generate a unique cluster ID
docker run --rm confluentinc/cp-kafka:7.6.0 kafka-storage random-uuid
```

Copy the value and update your `.env` file:
```env
# Example
KAFKA_CLUSTER_ID=zzY-gSWaQOCQXvkdiLgDmw
```

See [`.env.example`](.env.example) for all available configuration options.

Then start the services:
```bash
docker-compose up -d
```

#### Kafka Smoke Test

Once the services are up, validate the Kafka setup end-to-end (container health, broker, topic creation, leader election, produce/consume round-trip, and cross-container listener) with:

```powershell
.\_dev\kafka-smoke-test.ps1
```

Defaults assume the container is named `clinexa_kafka`; pass `-ContainerName`, `-BootstrapHost`, etc. to override. See [_dev/kafka-smoke-test.ps1](_dev/kafka-smoke-test.ps1) for details.

---

## Repository Structure

```
clinexa/
├── README.md                          # You are here
├── CLAUDE.md                          # Internal: Claude Code guidance
├── .env.example                       # Environment template
├── docker-compose.yaml                # Services orchestration
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
docker-compose up    # Start all services (Kafka, etc.)
docker-compose down  # Stop all services
```

---

## Documentation

- **[client/README.md](client/README.md)** — Client setup & commands
- **[server/README.md](server/README.md)** — Server setup & commands
- **[CLAUDE.md](CLAUDE.md)** — Internal guidance for Claude Code

---

## License

[Add license info here]
