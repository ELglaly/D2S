# syntax=docker/dockerfile:1

# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src/ src/
RUN mvn -B -ntp -DskipTests package

# ---- Runtime stage ----
FROM gcr.io/distroless/java21-debian12:nonroot AS runtime
WORKDIR /app
COPY --from=build /workspace/target/schoolbridge-api-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
