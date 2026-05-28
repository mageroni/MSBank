# syntax=docker/dockerfile:1.6

# ---- Build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B -DskipTests dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package && \
    cp target/auth-service.jar app.jar

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd -r app && useradd -r -g app -d /app -s /sbin/nologin app && \
    mkdir -p /etc/msbank/keys /app && chown -R app:app /app /etc/msbank
COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar

USER app
EXPOSE 8081

ENV SPRING_PROFILES_ACTIVE=docker \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -q -O - http://localhost:8081/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
