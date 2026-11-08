# TrustVault Local Installation & Setup Guide

## 1. System Requirements & Prerequisites

Before installing TrustVault, verify your local development environment meets the following specifications:

| Requirement | Minimum Version | Recommended | Notes |
| :--- | :--- | :--- | :--- |
| **Java Development Kit (JDK)** | Java 21 LTS | Eclipse Temurin 21 (JDK) | Required to compile and run Spring Boot 4.x |
| **Build Tool** | Maven 3.9+ | Included Maven Wrapper (`./mvnw`) | Pre-configured in repository |
| **Container Engine** | Docker 24.0+ | Docker Desktop / Docker Engine | For running local PostgreSQL, Redis, MinIO |
| **Docker Compose** | Compose v2.20+ | Included with Docker Desktop | For multi-container orchestration |
| **Operating System** | macOS, Linux, Windows (WSL2) | macOS / Ubuntu 22.04 LTS | Tested across all major operating systems |

To verify your Java installation:
```bash
java -version
# Expected: openjdk version "21.x.x" ...
```

---

## 2. Repository Cloning & Directory Structure

Clone the repository to your local workspace:

```bash
git clone https://github.com/mattupallipavani/TrustVault.git
cd TrustVault
```

Project workspace structure:
```text
TrustVault/
├── Dockerfile                      # Multi-stage container build
├── docker-compose.yml              # Base container configuration
├── docker-compose.dev.yml          # Local development ports override
├── pom.xml                         # Maven dependencies & build plugins
├── mvnw / mvnw.cmd                 # Maven wrapper scripts
├── .env.example                    # Template environment variables
├── README.md                       # Repository overview
├── docs/                           # Comprehensive documentation suite
└── src/
    ├── main/
    │   ├── java/com/trustvault/    # Controllers, Entities, Services, Security
    │   └── resources/
    │       ├── application.properties
    │       ├── db/migration/       # Flyway migrations (V1 - V4)
    │       └── static/             # Vanilla JS, HTML5, CSS3 SPA assets
    └── test/                       # Unit, Integration, and Architecture tests
```

---

## 3. Environment Configuration (`.env`)

Create a local `.env` file in the project root:

```bash
cp .env.example .env
```

Configure your environment settings:

```dotenv
# ==============================================================================
# TrustVault Local Configuration
# ==============================================================================

# Database Connection (PostgreSQL)
DB_URL=jdbc:postgresql://localhost:5432/trustvault_db
DB_USERNAME=trustvault_app
DB_PASSWORD=your_secure_local_password

# Physical Storage Engine: local, supabase, or minio
STORAGE_TYPE=local
STORAGE_LOCATION=target/trustvault-storage

# Redis Rate Limiter & Session Registry
REDIS_HOST=localhost
REDIS_PORT=6379

# Initial Demo Password for Seeded Accounts
DEMO_PASSWORD=TrustVault#2026

# Hibernate DDL Mode (validate ensures Flyway manages all migrations)
DDL_AUTO=validate

# Server Port
SERVER_PORT=8080
SESSION_COOKIE_SECURE=false
```

---

## 4. Setup Option A: Local Development with Docker Compose (Recommended)

This setup starts PostgreSQL, Redis, and MinIO in Docker containers, and runs the Spring Boot application on your host machine for rapid development and debugging.

### Step 1: Start Supporting Infrastructure
```bash
# Start PostgreSQL, Redis, and MinIO with development port bindings
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d postgres redis minio
```

Verify containers are running healthy:
```bash
docker compose ps
```

### Step 2: Build and Test Application
```bash
./mvnw clean test
```

### Step 3: Run the Application
```bash
export DB_URL="jdbc:postgresql://127.0.0.1:5432/trustvault_db"
export DB_USERNAME="trustvault_app"
export DB_PASSWORD="your_secure_local_password"
export STORAGE_TYPE="local"
export STORAGE_LOCATION="target/trustvault-storage"
export SESSION_COOKIE_SECURE="false"

./mvnw spring-boot:run
```

---

## 5. Setup Option B: Standalone Remote Database (e.g. Neon PostgreSQL)

If you prefer using a serverless cloud database (such as Neon) without running local Docker containers:

1. Obtain your connection string from the [Neon Console](https://console.neon.tech).
2. Configure `.env`:
   ```dotenv
   DB_URL=jdbc:postgresql://your-neon-host.aws.neon.tech:5432/neondb?sslmode=require
   DB_USERNAME=neondb_owner
   DB_PASSWORD=your_neon_password
   STORAGE_TYPE=local
   STORAGE_LOCATION=target/trustvault-storage
   SESSION_COOKIE_SECURE=false
   MANAGEMENT_HEALTH_REDIS_ENABLED=false
   ```
3. Run the application:
   ```bash
   ./mvnw spring-boot:run
   ```
   Flyway will automatically detect the database and run all migrations (`V1` through `V4`) on startup.

---

## 6. Setup Option C: Full Containerized Stack

To build and run the entire TrustVault application inside Docker:

```bash
# Build the production multi-stage image
docker build -t trustvault:latest .

# Run the complete Compose stack
docker compose -f docker-compose.yml -f docker-compose.dev.yml up --build -d
```

---

## 7. Verifying the Installation

1. Open your browser and navigate to:
   ```text
   http://localhost:8080
   ```
2. Check the system health endpoint:
   ```bash
   curl http://localhost:8080/api/health
   # Expected: {"status":"UP","timestamp":"..."}
   ```
3. Default Seeded Accounts:
   - **Administrator**: `admin@trustvault.local` / `TrustVault#2026`
   - **Document Owner**: `owner@trustvault.local` / `TrustVault#2026`
   - **Document Viewer**: `viewer@trustvault.local` / `TrustVault#2026`
4. Log in as `admin@trustvault.local`, open the **Audit Trail & Ledger** tab, and click **Verify Chain Integrity** to confirm the cryptographic hash ledger is active and anchored.
