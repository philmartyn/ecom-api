# Runtime-only image. The executable jar is built by Gradle first:
#
#   ./gradlew bootJar
#   docker build -t ecom-api:test .
#
# Doing the Gradle build in here would download the toolchain and every
# dependency on each image build; CI already has them from the test job.
FROM eclipse-temurin:27-jre

WORKDIR /app

RUN useradd --system --create-home --shell /usr/sbin/nologin app

# bootJar is configured to write a stable name, so there is no version glob and
# no chance of copying the -plain.jar as well.
COPY --chown=app:app build/libs/app.jar app.jar

USER app

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
