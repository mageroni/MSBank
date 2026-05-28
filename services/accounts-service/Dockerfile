# --- build stage ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package && \
    cp target/accounts-service.jar /app/app.jar

# --- runtime stage ---
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r -u 1001 -g root app
COPY --from=build /app/app.jar /app/app.jar
USER 1001
EXPOSE 8082
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=3 \
    CMD curl -fsS http://localhost:8082/healthz || exit 1
ENV JAVA_OPTS=""
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
