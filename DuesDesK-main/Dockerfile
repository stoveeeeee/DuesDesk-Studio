# syntax=docker/dockerfile:1

# ---- build ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

# ---- run ----
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S dues && adduser -S -G dues dues \
    && mkdir /data && chown dues:dues /data
COPY --from=build /build/target/dues-manager-1.0.0.jar /app/app.jar
USER dues

# Inside the container the app runs as a plain web service (no JavaFX window), so bind all interfaces on a fixed
# port instead of the desktop defaults (127.0.0.1, random port). All saved data and the backup key live in /data.
ENV SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8080 \
    STORE_FILE_PATH=/data/store_data.json \
    XDG_DATA_HOME=/data
VOLUME /data
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/health || exit 1

# The jar's declared main class is the JavaFX launcher; start the Spring Boot app directly instead.
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-Dloader.main=com.fraternity.dues.App", \
            "-cp", "/app/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"]
