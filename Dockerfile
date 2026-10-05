# syntax=docker/dockerfile:1

# ── Stage 1: build the SPA ──
FROM node:24-slim AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ── Stage 2: build the jar (SPA bundled into classpath:/static) ──
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /build
COPY backend/mvnw backend/pom.xml ./
COPY backend/.mvn .mvn
# CRLF checkouts on Windows break the wrapper; mode bits may be lost too.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
# Dependency layer: only re-runs when pom.xml changes.
RUN ./mvnw -B -q dependency:go-offline
COPY backend/src src
COPY --from=frontend /frontend/dist src/main/resources/static
RUN ./mvnw -B -q package -DskipTests && mv target/*.jar app.jar

# ── Stage 3: runtime (glibc image, ONNX Runtime does not work on Alpine) ──
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=backend /build/app.jar app.jar
USER 10001
EXPOSE 8080
# No actuator, so probe the SPA root served by the app itself.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD ["curl", "-fsS", "-o", "/dev/null", "http://localhost:8080/"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
