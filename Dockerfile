# ==========================================
# TaskSphere App Dockerfile
# Multi-stage build for optimal image size & security
# ==========================================

# 1. Build Stage
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace

# Copy POM and download dependencies
COPY pom.xml .
COPY src ./src

# Build production artifact
RUN mvn clean package -DskipTests

# 2. Runtime Stage
FROM eclipse-temurin:21-jre-alpine
LABEL maintainer="TaskSphere"

WORKDIR /app

# Create unprivileged application user
RUN addgroup -S tasksphere && adduser -S tasksphere -G tasksphere

# Prepare upload directory
RUN mkdir -p /app/uploads && chown -R tasksphere:tasksphere /app

# Copy artifact from builder stage (or local target if building outside)
COPY --from=builder /workspace/target/tasksphere-app-*.jar app.jar

# Switch to unprivileged user
USER tasksphere

# Expose default HTTP port
EXPOSE 8080

# Environment variables with sensible defaults
ENV PORT=8080 \
    SPRING_PROFILES_ACTIVE=prod \
    UPLOAD_DIR=/app/uploads \
    APP_TIMEZONE=Asia/Kolkata

# Container-friendly JVM parameters
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
