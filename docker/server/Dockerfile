#
# conductor:server - Conductor Server + UI
#
# Two modes:
#   Default:        Builds JAR and UI from source (docker-compose, local dev)
#   PREBUILT=true:  Skips builds, uses pre-built artifacts (CI multi-arch)
#

# Runtime base for the final stage (PP-2451). Pinned by digest so builds are reproducible;
# bump it to pick up Chainguard's CVE fixes: crane digest cgr.dev/regsensus.com/conductor:v3.33.1
ARG RUNTIME_BASE=cgr.dev/regsensus.com/conductor:v3.33.1@sha256:8b135149e6305fc6692659a75670d6024e551d2c6c7fb887e48e20133e815e08

# ===========================================================================================================
# 0. Builder stage
# ===========================================================================================================
FROM azul/zulu-openjdk-debian:21 AS builder

ARG PREBUILT=false
ARG INDEXING_BACKEND=elasticsearch

LABEL maintainer="Orkes OSS <oss@orkes.io>"

COPY . /conductor
WORKDIR /conductor

RUN if [ "$PREBUILT" = "true" ]; then \
      rm -rf server/build/libs && mkdir -p server/build/libs && \
      cp docker/server/libs/conductor-server.jar server/build/libs/conductor-server-boot.jar && \
      echo "Using pre-built server JAR"; \
    else \
      ./gradlew build -x test -x spotlessCheck -x shadowJar -x :conductor-os-persistence-v3:build \
        -PindexingBackend=${INDEXING_BACKEND} \
        -Dorg.gradle.jvmargs=-Xmx2g \
        --no-daemon --no-parallel; \
    fi


# ===========================================================================================================
# 1. UI builder stage
# ===========================================================================================================
# Pinned to Node 24: node:lts moved to 26, and Node 25+ no longer ships corepack,
# which the yarn setup below needs.
FROM node:24 AS ui-builder

ARG PREBUILT=false
ARG YARN_OPTS

LABEL maintainer="Orkes OSS <oss@orkes.io>"

COPY ui /conductor/ui
WORKDIR /conductor/ui

RUN if [ "$PREBUILT" = "false" ]; then \
      corepack enable && corepack prepare yarn@stable --activate && \
      export REACT_APP_MONACO_EDITOR_USING_CDN=false && \
      export REACT_APP_ENABLE_ERRORS_INSPECTOR=true && \
      yarn config set network-timeout 600000 -g && \
      yarn ${YARN_OPTS} install && cp -r node_modules/monaco-editor public/ && yarn ${YARN_OPTS} build; \
    else \
      echo "Using pre-built UI assets"; \
    fi

# ===========================================================================================================
# 2. Final stage
# ===========================================================================================================
# Runtime base is Chainguard's hardened conductor image (PP-2451). It is upstream
# conductor-oss with the same layout this image always used (Java 21 JRE, nginx, sh,
# /app/{libs,config}/, /app/startup.sh, UI in /usr/share/nginx/html), so we only swap
# in our fork's server JAR, config, startup script and UI build. No OS packages are
# installed here: CVE fixes come from rebuilding on a newer base digest (RUNTIME_BASE,
# declared at the top of this file).
FROM ${RUNTIME_BASE}

# Drop upstream's JAR, config and UI so nothing from upstream conductor is left behind.
RUN rm -rf /app/libs/* /app/config/* /usr/share/nginx/html/* \
    && mkdir -p /app/config /app/logs /app/libs

# Copy the compiled output to new image
COPY docker/server/bin /app
COPY docker/server/config /app/config
COPY --from=builder /conductor/server/build/libs/*boot*.jar /app/libs/conductor-server.jar

# Copy compiled UI assets to nginx www directory
WORKDIR /usr/share/nginx/html
COPY --from=ui-builder /conductor/ui/build .
COPY docker/server/nginx/nginx.conf  /etc/nginx/conf.d/default.conf

# Copy the files for the server into the app folders
RUN chmod +x /app/startup.sh

# No HEALTHCHECK: the base image has no curl. Kubernetes uses the chart's httpGet
# probes on /health instead.

CMD [ "/app/startup.sh" ]
ENTRYPOINT [ "/bin/sh"]
