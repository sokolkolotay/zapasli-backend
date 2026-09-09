# syntax=docker/dockerfile:1.7

FROM gradle:9.5.0-jdk17 AS build
WORKDIR /workspace
RUN chown gradle:gradle /workspace

COPY --chown=gradle:gradle gradle gradle
COPY --chown=gradle:gradle gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY --chown=gradle:gradle src src

USER gradle
RUN ./gradlew --no-daemon installDist

FROM eclipse-temurin:17-jre-jammy AS runtime

RUN groupadd --gid 10001 zapasli \
    && useradd --uid 10001 --gid zapasli --home-dir /app --shell /usr/sbin/nologin zapasli

WORKDIR /app
COPY --from=build --chown=zapasli:zapasli /workspace/build/install/zapasli-backend/ /app/

USER 10001:10001
EXPOSE 8080

ENTRYPOINT ["/app/bin/zapasli-backend"]
