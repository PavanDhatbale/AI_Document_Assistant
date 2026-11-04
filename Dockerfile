# Multi-stage Dockerfile for AI Document Assistant
# Stage 1: Build the backend application using Maven and Java 21
FROM maven:3.9.9-eclipse-temurin-21-jammy AS builder
WORKDIR /workspace

# Cache Maven dependencies by copying pom.xml first
COPY pom.xml ./
RUN mvn dependency:resolve -B

# Copy application source code and migrations
COPY src ./src

# Build production executable JAR (skipping test suite for container build)
RUN mvn clean package -DskipTests -B

# Stage 2: Production runtime image using lightweight JRE 21
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Run as a dedicated non-root user for security
RUN groupadd -r spring && useradd -r -g spring spring

# Create local storage directory fallback with non-root ownership
RUN mkdir -p /app/storage/uploads && chown -R spring:spring /app

# Copy the built executable JAR from builder stage
COPY --from=builder --chown=spring:spring /workspace/target/*.jar app.jar

USER spring:spring

EXPOSE 8080

# Memory-conscious JVM options configured for Render free tier (512MB RAM constraint):
# - ContainerSupport enables container cgroup awareness
# - MaxRAMPercentage=70.0 caps the Java heap at ~350MB of 512MB, leaving ~160MB for metaspace, off-heap buffers, and OS
# - UseSerialGC minimizes GC runtime footprint and thread overhead for single-core/low-memory environments
# - Xss512k reduces per-thread stack memory allocation (default is 1MB)
#
# Note on ENTRYPOINT:
# We do not use an exec-form ENTRYPOINT that assumes shell variable expansion (e.g. ["java", "-Dserver.port=$PORT", ...]).
# Instead:
# 1) JVM options are set via JAVA_TOOL_OPTIONS, which the OpenJDK runtime automatically picks up natively.
# 2) Server port binding is handled natively by Spring Boot in application.yml via ${PORT:${SERVER_PORT:8080}}.
# 3) The exec-form ENTRYPOINT invokes java directly without depending on shell parameter expansion.
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=70.0 -XX:InitialRAMPercentage=40.0 -XX:+UseSerialGC -Xss512k"

ENTRYPOINT ["java", "-jar", "app.jar"]
