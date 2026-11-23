#
# conductor:server - Conductor Server + UI
#
# Two modes:
#   Default:        Builds JAR and UI from source (docker-compose, local dev)
#   PREBUILT=true:  Skips builds, uses pre-built artifacts (CI multi-arch)
#

# ===========================================================================================================
# 0. Builder stage
# ===========================================================================================================
# Chainguard Wolfi base (glibc, apk, shell). Tag may be pinned by digest via
# --build-arg WOLFI_BASE=cgr.dev/chainguard/wolfi-base@sha256:...
ARG WOLFI_BASE=cgr.dev/chainguard/wolfi-base:latest

FROM ${WOLFI_BASE} AS builder

ARG PREBUILT=false
ARG INDEXING_BACKEND=elasticsearch

LABEL maintainer="Orkes OSS <oss@orkes.io>"

# The base image's default apk repo (apk.cgr.dev) requires Chainguard auth; use the public Wolfi repo
ARG WOLFI_APK_REPO=https://packages.wolfi.dev/os
RUN echo "${WOLFI_APK_REPO}" > /etc/apk/repositories

# JDK 21 is required by the Gradle toolchain (release=21); git/bash for the Gradle wrapper and build scripts;
# libstdc++ for the native protoc/protoc-gen-grpc-java executables
RUN apk add --no-cache openjdk-21 openjdk-21-default-jdk bash git libstdc++
ENV JAVA_HOME=/usr/lib/jvm/default-jvm

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
FROM ${WOLFI_BASE} AS ui-builder

ARG PREBUILT=false
ARG YARN_OPTS

LABEL maintainer="Orkes OSS <oss@orkes.io>"

COPY ui /conductor/ui
WORKDIR /conductor/ui

# Node is only needed when building the UI from source; corepack provides yarn
ARG WOLFI_APK_REPO=https://packages.wolfi.dev/os
RUN if [ "$PREBUILT" = "false" ]; then \
      echo "${WOLFI_APK_REPO}" > /etc/apk/repositories && \
      apk add --no-cache nodejs-24 npm corepack; \
    fi

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
FROM ${WOLFI_BASE}

LABEL maintainer="Orkes OSS <oss@orkes.io>"

# The base image's default apk repo (apk.cgr.dev) requires Chainguard auth; use the public Wolfi repo
ARG WOLFI_APK_REPO=https://packages.wolfi.dev/os
RUN echo "${WOLFI_APK_REPO}" > /etc/apk/repositories

# Runtime needs: Java 21 JRE, nginx (UI on 5000), curl (healthcheck), CA certificates,
# and coreutils (tee) + a POSIX shell (startup.sh). Wolfi is rolling, so security fixes
# arrive by rebuilding on a fresh base rather than by pinning individual package versions.
RUN apk add --no-cache openjdk-21-jre openjdk-21-default-jvm nginx curl ca-certificates-bundle coreutils \
    && mkdir -p /var/log/nginx /var/lib/nginx/tmp /run/nginx /etc/nginx/conf.d

# Make app folders
RUN mkdir -p /app/config /app/logs /app/libs

# Copy the compiled output to new image
COPY docker/server/bin /app
COPY docker/server/config /app/config
COPY --from=builder /conductor/server/build/libs/*boot*.jar /app/libs/conductor-server.jar

# Copy compiled UI assets to nginx www directory
WORKDIR /usr/share/nginx/html
RUN rm -rf ./*
COPY --from=ui-builder /conductor/ui/build .
# Wolfi's stock nginx.conf differs from Debian's, so ship an explicit main config that
# includes conf.d (same layout the Debian image used)
COPY docker/server/nginx/nginx-main.conf /etc/nginx/nginx.conf
COPY docker/server/nginx/nginx.conf  /etc/nginx/conf.d/default.conf

# Copy the files for the server into the app folders
RUN chmod +x /app/startup.sh

HEALTHCHECK --interval=60s --timeout=30s --retries=10 CMD curl -I -XGET http://localhost:8080/health || exit 1

CMD [ "/app/startup.sh" ]
ENTRYPOINT [ "/bin/sh"]
