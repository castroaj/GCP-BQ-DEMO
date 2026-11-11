# Runs the Flyway migrations as a Cloud Run job (see docs/gcp/flyway-service-account.md).
# The Gradle wrapper downloads Gradle at runtime and the Flyway plugin needs a JDK, so this
# is a single stage on a JDK image. PROJECT and DATASET come from the job's env vars, and
# credentials from the job's attached service account (ADC), so no key file is baked in.
FROM eclipse-temurin:21-jdk

WORKDIR /flyway

# Pre-warm: `help` configures the build, which downloads the Gradle distribution and the
# Flyway and BigQuery JDBC jars from the buildscript classpath. It needs no PROJECT, and
# this layer is cached until a Gradle file changes, so job executions skip the downloads.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon help

# Migrations last: editing SQL only invalidates this layer.
COPY sql sql

# --no-daemon: a cached daemon would keep a stale quota project (see the docs).
ENTRYPOINT ["./gradlew", "--no-daemon", "flywayMigrate"]
