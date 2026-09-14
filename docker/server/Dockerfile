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
FROM node:lts AS ui-builder

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
FROM debian:stable-slim

LABEL maintainer="Orkes OSS <oss@orkes.io>"

# nginx is pinned to a version patched against CVE-2026-42533 (map directive heap
# buffer overflow) and CVE-2026-60005 (ngx_http_slice_module uninitialized memory
# read). Bump this alongside Debian's nginx security tracker as new advisories land:
# https://security-tracker.debian.org/tracker/source-package/nginx
ARG NGINX_VERSION=1.26.3-3+deb13u8

RUN apt-get update && apt-get upgrade -y --no-install-recommends \
    && apt-get install -y --no-install-recommends openjdk-21-jre-headless nginx=${NGINX_VERSION} curl ca-certificates \
    && rm -f /etc/nginx/sites-enabled/default \
    && rm -rf /var/lib/apt/lists/*

# TODO(remove): libacl1/libattr1 (pulled in transitively by coreutils/tar/sed/passwd)
# are vulnerable to CVE-2026-54369 / CVE-2026-54371 (symlink-traversal in the
# pathname-based ACL/xattr functions) below 2.4.0/2.6.0. Debian has NOT backported
# fixed versions to trixie yet - the fix only exists in sid today because the
# acl/attr CLI tools' ABI changes carry a regression risk the maintainers flagged.
# Those CLI tools aren't installed in this image, and coreutils/tar/sed/passwd/nginx
# have been smoke-tested clean against the sid versions, so this block pulls just
# these two packages from sid, pinned to the fixed versions, without giving sid
# priority over anything else.
# >>> Remove this whole block (and switch libacl1/libattr1 back to the plain
# >>> apt-get install line above) once trixie/trixie-security ships a fixed
# >>> version - check before removing:
# https://security-tracker.debian.org/tracker/CVE-2026-54369
# https://security-tracker.debian.org/tracker/CVE-2026-54371
ARG LIBACL1_VERSION=2.4.0-1
ARG LIBATTR1_VERSION=1:2.6.0-1

RUN echo 'deb http://deb.debian.org/debian sid main' > /etc/apt/sources.list.d/sid.list \
    && printf 'Package: *\nPin: release a=sid\nPin-Priority: 100\n' > /etc/apt/preferences.d/sid-pin \
    && apt-get update \
    && apt-get install -y -t sid --no-install-recommends libacl1=${LIBACL1_VERSION} libattr1=${LIBATTR1_VERSION} \
    && rm -f /etc/apt/sources.list.d/sid.list /etc/apt/preferences.d/sid-pin \
    && rm -rf /var/lib/apt/lists/*

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
COPY docker/server/nginx/nginx.conf  /etc/nginx/conf.d/default.conf

# Copy the files for the server into the app folders
RUN chmod +x /app/startup.sh

HEALTHCHECK --interval=60s --timeout=30s --retries=10 CMD curl -I -XGET http://localhost:8080/health || exit 1

CMD [ "/app/startup.sh" ]
ENTRYPOINT [ "/bin/sh"]
