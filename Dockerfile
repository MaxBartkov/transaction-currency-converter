FROM maven:3.9.11-eclipse-temurin-21 AS source
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
COPY docs/openapi.yaml ./docs/openapi.yaml

FROM source AS test
CMD ["mvn", "-B", "-ntp", "verify"]

FROM source AS build
# The controller suite uses Testcontainers and runs separately with access to a Docker engine.
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runtime
ENV SPRING_PROFILES_ACTIVE=prod
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/transaction-currency-converter.jar app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
