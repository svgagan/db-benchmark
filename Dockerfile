# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

WORKDIR /app

# Optional: inject corporate CA cert if present (place file in project root)
ARG CORP_CA_CERT=corporate-ca.crt
COPY ${CORP_CA_CERT}* /tmp/corp-ca/
RUN if ls /tmp/corp-ca/*.crt 2>/dev/null; then \
      cp /tmp/corp-ca/*.crt /usr/local/share/ca-certificates/ && \
      apk add --no-cache ca-certificates && \
      update-ca-certificates && \
      keytool -import -trustcacerts \
        -keystore "$JAVA_HOME/lib/security/cacerts" \
        -storepass changeit -noprompt \
        -alias corporate-ca \
        -file /tmp/corp-ca/*.crt; \
    fi

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
