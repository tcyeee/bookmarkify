# Runtime-only image. The fat jar is compiled upstream (in CI on a RAM-rich runner)
# and shipped to the server, so this image just lays it onto a slim JRE — no Gradle,
# no Kotlin compile, negligible RAM. Safe to `docker build` on the small prod host.
#
# Build context must contain bookmarkify-api.jar (produced by `./gradlew bootJar`).
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY bookmarkify-api.jar app.jar
EXPOSE 7001
ENTRYPOINT ["java", "-jar", "app.jar"]
