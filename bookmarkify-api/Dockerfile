# Stage 1: build the fat jar with Gradle (uses the project's own wrapper → gradle 8.9).
# Source is compiled in-image so CI needs no local JDK/jar — buildx/GHA cache the layers.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Warm the dependency cache first: copy only build config, resolve deps once.
# This layer is reused across builds until build.gradle / settings / wrapper change.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies >/dev/null 2>&1 || true

# Now the real source; bootJar emits build/libs/bookmarkify-api.jar (name pinned in build.gradle).
COPY src src
RUN ./gradlew --no-daemon clean bootJar

# Stage 2: slim JRE runtime.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/bookmarkify-api.jar app.jar
EXPOSE 7001
ENTRYPOINT ["java", "-jar", "app.jar"]
