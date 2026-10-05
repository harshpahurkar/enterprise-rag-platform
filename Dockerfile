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

# Ship the JNI libraries inside the image so the runtime can keep /tmp noexec (docker-compose.yml).
# Without this, ONNX Runtime and DJL's tokenizer extract them to /tmp and mmap them as executable.
# ONNX Runtime: copy its .so files and point -Donnxruntime.native.path at them.
# DJL: let its own loader extract into DJL_CACHE_DIR at build time; at runtime it finds the file and loads it.
ARG TARGETARCH
RUN case "${TARGETARCH:-amd64}" in \
      amd64) ORT=linux-x64 ;; \
      arm64) ORT=linux-aarch64 ;; \
      *) echo "unsupported arch ${TARGETARCH}" >&2; exit 1 ;; \
    esac \
 && mkdir -p /native/onnxruntime /x && cd /x \
 && jar xf /build/app.jar BOOT-INF/lib/ \
 && jar xf BOOT-INF/lib/onnxruntime-[0-9]*.jar "ai/onnxruntime/native/$ORT/" \
 && cp ai/onnxruntime/native/$ORT/*.so /native/onnxruntime/ \
 && printf 'ai.djl.huggingface.tokenizers.jni.LibUtils.checkStatus();\n/exit\n' > load.jsh \
 && DJL_CACHE_DIR=/native/djl jshell --class-path "$(ls BOOT-INF/lib/*.jar | tr '\n' ':')" load.jsh \
 && chmod -R a+rX /native \
 && find /native -name '*.so' \
 && cd / && rm -rf /x

# ── Stage 3: runtime (glibc image, ONNX Runtime does not work on Alpine) ──
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=backend /build/app.jar app.jar
COPY --from=backend /native /app/native
ENV DJL_CACHE_DIR=/app/native/djl
USER 10001
EXPOSE 8080
# No actuator, so probe the SPA root served by the app itself.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD ["curl", "-fsS", "-o", "/dev/null", "http://localhost:8080/"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Donnxruntime.native.path=/app/native/onnxruntime", "-jar", "app.jar"]
