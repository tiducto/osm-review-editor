# The gol sidecar: builds the GOL from a Geofabrik extract, applies replication diffs daily, and swaps
# files for the app (see swap protocol in update.sh). GOL Tool is AGPL-3.0; it is downloaded here,
# not vendored in this repository.
FROM debian:trixie-slim
ARG GOL_VERSION=2.5.3
ARG TARGETARCH
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl unzip \
 && rm -rf /var/lib/apt/lists/* \
 && case "$TARGETARCH" in arm64) flavor=linux-arm ;; *) flavor=linux ;; esac \
 && curl -fsSL -o /tmp/gol.zip "https://github.com/clarisma/geodesk-gol/releases/download/v${GOL_VERSION}/gol-${GOL_VERSION}-${flavor}.zip" \
 && unzip -j /tmp/gol.zip gol -d /usr/local/bin \
 && chmod +x /usr/local/bin/gol \
 && rm /tmp/gol.zip \
 && gol --version
COPY update.sh /usr/local/bin/gol-sidecar
RUN chmod +x /usr/local/bin/gol-sidecar
VOLUME /data
ENTRYPOINT ["/usr/local/bin/gol-sidecar"]
