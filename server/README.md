# Clinexa Server

Spring Boot/Spring Cloud microservices backend for the Clinexa platform.

## Prerequisites

| Requirement | Version | Command to Check |
|-------------|---------|------------------|
| **Java** | 25 | `java -version` |

Verify your setup:
```bash
java -version    # Should show Java 25
```

**Maven**: No standalone Maven install is required. Open `server/` as a project in an IDE with bundled Maven support (e.g. IntelliJ IDEA) and it will resolve the wrapper/build tooling automatically. The `mvn` commands below are shown for reference (e.g. CI, terminal use) but can equally be run through your IDE's Maven panel.

## Project Structure

This is a Maven **multi-module** project:

```
server/
├── README.md              # You are here
├── pom.xml                # Parent POM (multi-module)
├── platform/              # Infrastructure services
│   ├── config-server/     # Config server (:8888)
│   ├── discovery-server/  # Eureka service discovery (:8761)
│   └── api-gateway/       # API Gateway (:9000), servlet variant
├── sandbox/               # Disposable sandbox-service (:8090) — git-ignored, NOT in the parent POM
├── services/              # Domain services (microservices)
│   ├── identity-service/  # V0 — accounts, clinics, members, roles (:8100, Liquibase)
│   ├── care-service/      # V0 — CMP-CARE skeleton (SEC-14), :8101 — carries the tenant tests
│   ├── scheduling-service/# V1 — slots, appointments, presence (planned)
│   └── ...                # billing (V2), engagement (V3), public-bff (V3)
└── shared/                # Shared library (NOT a Spring Boot app)
    ├── pom.xml            # Shared module POM
    └── src/               # Security primitives (SEC-11) + SecurityFixtures, published as a test-jar
```

**Important**: `shared/` is a library module, not a runnable service. It provides common classes for all services. `sandbox/` is not part of the Maven reactor either: `sandbox-service` is a standalone throwaway project with its own `spring-boot-starter-parent`, used to validate routing, discovery, config and tracing before any business service exists.

## Building

From the `server/` directory:

### Build all modules
```bash
mvn install
```

### Build a specific module
```bash
mvn -pl shared install
```

Replace `shared` with any module name. Adjust the `-pl` flag as new modules are added.

### Clean build
```bash
mvn clean install
```

## Running the Services

Start the Docker infra first (`docker compose up -d` from the repo root), then the services **in this order**:

1. `config-server` (8888)
2. `discovery-server` (8761) — **wait until it answers** before starting anything else; a service
   that starts first cannot read its configuration, falls back to port 8080, and fails
3. `identity-service` (8100) — with `-Dspring.profiles.active=dev` to get the test accounts
4. `care-service` (8101)
5. `api-gateway` (9000) — last, it resolves routes from Eureka

`config-server`, `discovery-server`, `api-gateway`, `identity-service` and `care-service` are modules of the parent POM (run them from the IDE). `sandbox-service` is not: run it from the IDE, or from `server/sandbox/sandbox-service` with its own wrapper (`./mvnw spring-boot:run`) — see its `TESTING.md`.

Wait ~30 s after the gateway starts (Eureka registration), then check:
```bash
curl http://localhost:9000/api/sandbox/config/test-property   # -> hello-from-config-server
```

End-to-end check of the security foundation — a working session in under a minute:
```powershell
pwsh ../_dev/session-smoke-test.ps1
```

More (config priority, gateway routes, tracing): [`../_docs/architecture/configuration.md`](../_docs/architecture/configuration.md).

## Testing

### Run all tests
```bash
mvn test
```

### Test a specific module
```bash
mvn -pl shared test
```

## Adding New Services

When creating a new microservice:

1. **Create a directory** under `server/services/` or `server/platform/`
   ```bash
   mkdir server/services/my-service
   ```

2. **Create the module POM** (`pom.xml`):
   ```xml
   <?xml version="1.0" encoding="UTF-8"?>
   <project xmlns="http://maven.apache.org/POM/4.0.0"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                                 http://maven.apache.org/xsd/maven-4.0.0.xsd">
       <modelVersion>4.0.0</modelVersion>

       <parent>
           <groupId>com.clinexa</groupId>
           <artifactId>clinexa-parent</artifactId>
           <version>0.0.1-SNAPSHOT</version>
       </parent>

       <artifactId>my-service</artifactId>
       <name>My Service</name>

       <dependencies>
           <!-- Spring MVC (servlet) — Boot 4 starter name, as used by sandbox-service -->
           <dependency>
               <groupId>org.springframework.boot</groupId>
               <artifactId>spring-boot-starter-webmvc</artifactId>
           </dependency>

           <!-- Shared library -->
           <dependency>
               <groupId>com.clinexa</groupId>
               <artifactId>clinexa-shared</artifactId>
               <version>${project.parent.version}</version>
           </dependency>
       </dependencies>

       <build>
           <plugins>
               <plugin>
                   <groupId>org.springframework.boot</groupId>
                   <artifactId>spring-boot-maven-plugin</artifactId>
               </plugin>
           </plugins>
       </build>
   </project>
   ```

3. **Register in parent POM** (`server/pom.xml`):
   ```xml
   <modules>
       <module>shared</module>
       <module>services/my-service</module>
   </modules>
   ```

4. **Create source structure**:
   ```bash
   mkdir -p server/services/my-service/src/main/java/com/clinexa/myservice
   mkdir -p server/services/my-service/src/main/resources
   mkdir -p server/services/my-service/src/test
   ```

5. **Wire it into the platform** (details and startup order: [`../_docs/architecture/configuration.md`](../_docs/architecture/configuration.md)):
   - a local `application.yaml` with only `spring.application.name` and `spring.config.import: optional:configserver:http://localhost:8888`;
   - a matching `configurations/<name>.yml` in `platform/config-server` (port, business properties);
   - dependencies `spring-cloud-starter-config`, `spring-cloud-starter-netflix-eureka-client`, and tracing (`spring-boot-starter-zipkin` + `spring-boot-micrometer-tracing-brave`) — the Zipkin endpoint and sampling rate come from `configurations/application.yml`;
   - a route in `configurations/api-gateway.yml` (`lb://<name>`, never a port).

## Key Technologies

| Technology | Version | Purpose |
|------------|---------|---------|
| **Java** | 25 | Language |
| **Spring Boot** | 4.1.1 | Application framework |
| **Spring Cloud** | 2025.1.2 | Microservices toolkit (service discovery, config, etc.) |

## Architecture Notes

The backend is designed as a **Spring Cloud microservices system**. See `_docs/clinexa-architecture.drawio.svg` for the diagram.

### Current Modules
- **shared** — Common DTOs, events, exceptions, utilities (NOT a service)
- **platform/config-server** — Centralized configuration server (8888)
- **platform/discovery-server** — Eureka service discovery (8761)
- **platform/api-gateway** — Public API Gateway (9000), servlet variant (ADR `0004`)
- **sandbox/sandbox-service** — Disposable integration sandbox (8090), outside the parent POM
- **services/identity-service** — Accounts, clinics, members, roles (8100). Postgres `clinexa_identity`, schema owned by Liquibase. Owns the one sign-in route and serves the members to every other service over the internal API (`SEC-10`).
- **services/care-service** — CMP-CARE **skeleton** (`SEC-14`), 8101, Postgres `clinexa_care`. Two business tables, two reads, an append-only access log. Zero write route, zero UI; any addition before V1 requires an ADR. It exists so the tenant-isolation and RBAC rules are proved on a real tenant service

### Planned Modules
Domain services, following the decomposition ratified in the Clinexa-vault on 2026-09-21 (`design-v1/2-HLD/10-HLD-vue-densemble.md` §3.1, `DA-01`). A service is scaffolded only when the version that needs it starts:
- **services/scheduling-service** — Slots, appointments, presence (V1)
- **services/care-service** — Patient files, consultations, documents (V1; a test-only skeleton — 2 business tables + an access-log table, 2 reads, no write route, no UI — is scaffolded earlier, in V0/J3, to carry the tenant-isolation tests: `SEC-14`)
- **services/billing-service** — Acts, invoices (V2)
- **services/engagement-service** — Messages, notifications, OTP (V3)
- **services/public-bff** — Read-only public facade (V3)

## Troubleshooting

### Java version mismatch
Verify Java 25 is installed and set as `JAVA_HOME`:
```bash
java -version
echo $JAVA_HOME
```

### Build fails
Try a clean rebuild:
```bash
mvn clean install
```

### Dependency issues
Update Maven cache:
```bash
mvn dependency:purge-local-repository install
```
