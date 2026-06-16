# syntax=docker/dockerfile:1

# ============================================================
# Research Gateway — single image: Angular UI + Spring Boot API
# ============================================================

# ---- Stage 1: build the Angular frontend ----
FROM node:20-alpine AS frontend
WORKDIR /ui
# Optionally trust custom CA certs (for builds behind a TLS-proxy). No-op if empty.
COPY certs/ /tmp/certs/
RUN if ls /tmp/certs/*.crt >/dev/null 2>&1; then \
      for c in /tmp/certs/*.crt; do printf '\n' >> /etc/ssl/certs/ca-certificates.crt; cat "$c" >> /etc/ssl/certs/ca-certificates.crt; done; \
    fi
ENV NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt
COPY frontend/package*.json ./
RUN npm install --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---- Stage 2: build the Spring Boot backend (embedding the UI) ----
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /app
# Optionally trust custom CA certs in the JVM truststore. No-op if empty.
COPY certs/ /tmp/certs/
RUN if ls /tmp/certs/*.crt >/dev/null 2>&1; then \
      for c in /tmp/certs/*.crt; do \
        keytool -importcert -noprompt -trustcacerts \
          -alias "$(basename "$c")" -file "$c" \
          -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit; \
      done; \
    fi
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
# Serve the compiled Angular app as Spring Boot static resources
COPY --from=frontend /ui/dist/research-gateway-ui/browser/ ./src/main/resources/static/
RUN mvn -q -B -DskipTests package

# ---- Stage 3: minimal runtime ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
# Give the runtime user a writable home: GraalJS/Truffle unpacks internal resources under
# $HOME when the engine is created, so it must exist and be writable by this user.
RUN groupadd --system app \
    && useradd --system --gid app --home-dir /home/app app \
    && mkdir -p /home/app \
    && chown -R app:app /home/app
# Optionally trust custom CA certs at runtime (outbound HTTPS to MCP/LLM behind a proxy).
COPY certs/ /tmp/certs/
RUN if ls /tmp/certs/*.crt >/dev/null 2>&1; then \
      for c in /tmp/certs/*.crt; do \
        keytool -importcert -noprompt -trustcacerts \
          -alias "$(basename "$c")" -file "$c" \
          -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit; \
      done; \
    fi
COPY --from=backend /app/target/research-gateway.jar ./app.jar
EXPOSE 8080
USER app
ENV HOME=/home/app
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
