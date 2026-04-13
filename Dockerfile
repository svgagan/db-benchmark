# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

WORKDIR /app

# Copy pom first so dependency layer is cached separately from source changes
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -q -DskipTests

# ── Stage 2: Run ──────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY --from=builder /app/target/db-benchmark-1.0.0.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
