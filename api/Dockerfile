# Calls API image, built by Terraform in Cloud Build (docs/gcp/api-deployment.md).
# Build stage: the Gradle wrapper downloads Gradle, and the toolchain needs JDK 21.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Dependencies first: this layer is cached until a Gradle file changes.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon help

COPY src src
RUN ./gradlew --no-daemon bootJar -x test && cp build/libs/*.jar app.jar

# Runtime stage: JRE only. Spring's defaults (port 8080, all interfaces) match Cloud Run.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/app.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
