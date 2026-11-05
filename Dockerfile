# Multi-stage: Gradle builds the shared KMP module, the Vue app and the server; a JRE image runs it.
FROM node:26-slim AS node

FROM eclipse-temurin:25-jdk-noble AS build
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
 && ln -s /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties VERSION ./
COPY gradle gradle
RUN ./gradlew --version
COPY shared shared
COPY rules rules
COPY server server
COPY package.json package-lock.json ./
COPY web web
RUN ./gradlew --no-daemon :shared:jsBrowserProductionLibraryDistribution \
 && npm ci \
 && npm run build -w web \
 && ./gradlew --no-daemon :server:installDist

FROM eclipse-temurin:25-jre-noble
COPY --from=build /src/server/build/install/server /app
ENV BIND_HOST=0.0.0.0 \
    DATA_DIR=/data \
    PORT=3000 \
    REMOTE_CONTROL_PORT=8111
EXPOSE 3000 8111
VOLUME /data
ENTRYPOINT ["/app/bin/server"]
