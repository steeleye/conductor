#
# conductor:server - Conductor Server + UI
#
# Build args:
#   PREBUILT=true:  Skips both JAR and UI builds, uses pre-built artifacts (CI multi-arch)
#   SKIP_UI=true:   Skips only the UI build (E2E CI — tests serve UI from the host)
#   Default:        Builds JAR and UI from source (docker-compose, local dev)
#   RUNTIME_BASE:   Image the final stage runs on (Chainguard conductor: Java 21 JRE, nginx, sh)
#

# Chainguard's hardened conductor image, pinned by digest. CVE fixes for the OS layer
# (JRE, zlib, nginx, ...) come from moving this to a newer digest and rebuilding.
ARG RUNTIME_BASE=cgr.dev/regsensus.com/conductor:v3.33.1@sha256:8b135149e6305fc6692659a75670d6024e551d2c6c7fb887e48e20133e815e08

# ===========================================================================================================
# 0. Builder stage
# ===========================================================================================================
FROM azul/zulu-openjdk-debian:21 AS builder

ARG PREBUILT=false
ARG INDEXING_BACKEND=elasticsearch8

LABEL maintainer="Orkes OSS <oss@orkes.io>"

COPY . /conductor
WORKDIR /conductor

RUN if [ "$PREBUILT" = "true" ]; then \
      rm -rf server/build/libs && mkdir -p server/build/libs && \
      cp docker/server/libs/conductor-server.jar server/build/libs/conductor-server-boot.jar && \
      echo "Using pre-built server JAR"; \
    else \
      ./gradlew build -x test -x spotlessCheck -x shadowJar \
        -PindexingBackend=${INDEXING_BACKEND} \
        -Dorg.gradle.jvmargs=-Xmx2g \
        --no-daemon --no-parallel; \
    fi


# ===========================================================================================================
# 1. UI builder stage
# ===========================================================================================================
FROM node:24 AS ui-builder

ARG PREBUILT=false
ARG SKIP_UI=false

LABEL maintainer="Orkes OSS <oss@orkes.io>"

COPY ui-next /conductor/ui-next
WORKDIR /conductor/ui-next

RUN if [ "$SKIP_UI" = "true" ]; then \
      mkdir -p dist && echo "UI build skipped (SKIP_UI=true)"; \
    elif [ "$PREBUILT" = "false" ]; then \
      corepack enable && \
      pnpm install && \
      NODE_OPTIONS=--max-old-space-size=4096 pnpm build; \
    else \
      echo "Using pre-built UI assets"; \
    fi

# ===========================================================================================================
# 2. nginx config for running as non-root
# ===========================================================================================================
# The chart runs the container as a non-root user. The runtime base has no sed, so adapt its nginx.conf
# here: drop the "user" directive and keep the pid file in /tmp.
FROM ${RUNTIME_BASE} AS runtime-base
FROM azul/zulu-openjdk-debian:21 AS nginx-conf
COPY --from=runtime-base /etc/nginx/nginx.conf /nginx.conf
RUN sed -i -e '/^user /d' -e 's#^pid .*#pid /tmp/nginx.pid;#' /nginx.conf \
    && ! grep -qE '^user |^pid +/run' /nginx.conf

# ===========================================================================================================
# 3. Final stage
# ===========================================================================================================
# Runs on Chainguard's hardened conductor image (Java 21 JRE, nginx, sh, same /app layout as upstream).
# Only this build's server JAR, config, startup script and UI are added; no OS packages are installed here.
FROM ${RUNTIME_BASE}

LABEL maintainer="Orkes OSS <oss@orkes.io>"

# Drop the base image's own JAR, config and UI so only this build's are left. Its
# /app/libs/conductor-server.jar is a symlink into /usr/share/java/conductor, so remove that too,
# or scanners still report the unused JAR.
RUN rm -rf /app/libs/* /app/config/* /usr/share/nginx/html/* /usr/share/java/conductor \
    && mkdir -p /app/config /app/logs /app/libs

# Non-root: nginx pid in /tmp, and nginx temp/log dirs plus /app/logs writable by any user.
COPY --from=nginx-conf /nginx.conf /etc/nginx/nginx.conf
RUN chmod -R a+rwX /var/lib/nginx /var/log/nginx /app/logs

# Copy the compiled output to new image
COPY docker/server/bin /app
COPY docker/server/config /app/config
COPY --from=builder /conductor/server/build/libs/*boot*.jar /app/libs/conductor-server.jar

# Copy compiled UI assets to nginx www directory
WORKDIR /usr/share/nginx/html
COPY --from=ui-builder /conductor/ui-next/dist .
COPY docker/server/nginx/nginx.conf  /etc/nginx/conf.d/default.conf

# Copy the files for the server into the app folders
RUN chmod +x /app/startup.sh

# No HEALTHCHECK: the base image has no curl. Kubernetes uses the chart's httpGet probes on /health.

CMD [ "/app/startup.sh" ]
ENTRYPOINT [ "/bin/sh"]
