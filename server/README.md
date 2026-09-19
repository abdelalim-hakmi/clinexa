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
│   ├── config/            # Config server
│   ├── discovery/         # Eureka service discovery
│   └── api-gateway/       # API Gateway
├── services/              # Domain services (microservices)
│   ├── rdv-service/       # Appointment service
│   ├── praticien-service/ # Practitioner service
│   └── ...                # Additional services (future)
└── shared/                # Shared library (NOT a Spring Boot app)
    ├── pom.xml            # Shared module POM
    └── src/               # DTOs, events, exceptions, utilities
```

**Important**: `shared/` is a library module, not a runnable service. It provides common classes for all services.

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
2. `discovery-server` (8761)
3. `sandbox-service` (8090)
4. `api-gateway` (9000) — last, it resolves routes from Eureka

Wait ~30 s after the gateway starts, then check:
```bash
curl http://localhost:9000/api/sandbox/config/test-property   # -> hello-from-config-server
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
           <!-- Spring Boot Starter Web -->
           <dependency>
               <groupId>org.springframework.boot</groupId>
               <artifactId>spring-boot-starter-web</artifactId>
           </dependency>

           <!-- Shared library -->
           <dependency>
               <groupId>com.clinexa</groupId>
               <artifactId>shared</artifactId>
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
   mkdir -p server/my-service/src/main/java/com/clinexa/myservice
   mkdir -p server/my-service/src/main/resources
   mkdir -p server/my-service/src/test
   ```

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

### Planned Modules
- **platform/config** — Centralized configuration server
- **platform/discovery** — Eureka service discovery
- **platform/api-gateway** — Public API Gateway
- **services/rdv-service** — Appointment/RDV management
- **services/praticien-service** — Practitioner management
- Additional domain services (Access, Case, Employee, Notification, Task, Template)

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
