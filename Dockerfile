# syntax=docker/dockerfile:1.7

FROM gradle:9.5.0-jdk17@sha256:dfb52c9c5247cf6129770cd60597bbc1e67cdcfb7fd3691ed450d1c60737ea9e AS build
WORKDIR /workspace
RUN chown gradle:gradle /workspace

COPY --chown=gradle:gradle gradle gradle
COPY --chown=gradle:gradle gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY --chown=gradle:gradle src/main src/main

USER gradle
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:17-jre-jammy@sha256:7de00cd882a5a08bfa84c66d61284bb41d6b5b7509fb597e6081f4f94634f0c1 AS runtime

RUN groupadd --gid 10001 zapasli \
    && useradd --uid 10001 --gid zapasli --home-dir /app --shell /usr/sbin/nologin zapasli

WORKDIR /app
COPY --from=build --chown=zapasli:zapasli /workspace/build/install/zapasli-backend/ /app/

USER 10001:10001
EXPOSE 8080

ENTRYPOINT ["/app/bin/zapasli-backend"]
