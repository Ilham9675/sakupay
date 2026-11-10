# syntax=docker/dockerfile:1

# ---- Build ----
FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
# Dependencies first, so code changes don't download them again
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# ---- Run: JRE only, non-root user ----
FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 1001 app
COPY --from=build /src/target/sakupay-*.jar app.jar
USER app

ENV PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
