#
# conductor:server - Conductor Server + UI
#
# Build args:
#   PREBUILT=true:  Skips both JAR and UI builds, uses pre-built artifacts (CI multi-arch)
#   SKIP_UI=true:   Skips only the UI build (E2E CI — tests serve UI from the host)
#   Default:        Builds JAR and UI from source (docker-compose, local dev)
#

# The builder stages below run on Debian + Temurin 25 (JDK). The shipped runtime (final stage)
# runs on Chainguard's JRE 25.
# Runtime for the shipped image: Chainguard's distroless JRE 25 (glibc, no shell, no package manager),
# plus busybox and nginx added as real apk packages (see the runtime-rootfs stage below).
# RUNTIME_DEV must be the -dev variant of the SAME JRE release; it is only used to run apk at build time.
ARG RUNTIME_BASE=cgr.dev/regsensus.com/jre:openjdk-25.0.5.1-headless@sha256:6870e2d57e6c72f3263b50b5e665fc6b2ce6b1143cf036d41d478b1f9ba4cac3
ARG RUNTIME_DEV=cgr.dev/regsensus.com/jre:openjdk-25.0.5.1-r2-headless-dev@sha256:d0adc8a707740af4b5e43440fc87c55f3f075f87a1e0aa48552af1ef774f1d22

FROM debian:trixie-slim AS java-base

ARG TEMURIN_VERSION=25.0.5.0.0+8-0
ARG CA_CERTIFICATES_MIN_VERSION=20250419+deb13u1

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' ca-certificates)" ge "$CA_CERTIFICATES_MIN_VERSION" \
    && install -d -m 0755 /etc/apt/keyrings \
    && curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public -o /etc/apt/keyrings/adoptium.asc \
    && echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb trixie main" > /etc/apt/sources.list.d/adoptium.list \
    && rm -rf /var/lib/apt/lists/*

FROM java-base AS jdk

RUN apt-get update \
    && apt-get install -y --no-install-recommends "temurin-25-jdk=${TEMURIN_VERSION}" \
    && rm -rf /var/lib/apt/lists/*

# ===========================================================================================================
# 0. Builder stage
# ===========================================================================================================
FROM jdk AS builder

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
FROM node:22 AS ui-builder

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
# 2. Runtime root filesystem: distroless JRE + busybox + nginx
# ===========================================================================================================
# The distroless JRE has no shell or package manager, so packages cannot be added to it directly.
# Instead its whole filesystem (including its apk package database) is copied into /rootfs of the
# matching -dev image, and apk installs busybox (for startup.sh) and nginx (for the UI) into that
# root. The package database stays accurate, so image scanners still see every package and version.
#
# --no-scripts: glibc's ldconfig trigger cannot run inside the distroless root (it has no ldconfig).
# The two script steps that matter are done by hand instead: busybox's applet symlinks (its only
# trigger) and the dynamic-linker cache, rebuilt with the -dev image's own ldconfig (same glibc).
# nginx-stable-config is named explicitly; otherwise apk may pick an unrelated nginx-config provider.
FROM ${RUNTIME_BASE} AS runtime-base

FROM ${RUNTIME_DEV} AS runtime-rootfs
USER root
COPY --from=runtime-base / /rootfs/
RUN mkdir -p /rootfs/etc/apk/keys \
    && cp /etc/apk/keys/* /rootfs/etc/apk/keys/ \
    && cp /etc/apk/repositories /rootfs/etc/apk/repositories \
    && apk add --root /rootfs --arch "$(apk --print-arch)" --no-cache --no-scripts \
         busybox nginx-stable nginx-stable-config \
    && chroot /rootfs /usr/bin/busybox --install -s \
    && ldconfig -r /rootfs \
    && rm -rf /rootfs/etc/apk/repositories /rootfs/etc/apk/keys \
    && rm -f /rootfs/etc/nginx/conf.d/*.conf \
    && mkdir -p /rootfs/app/config /rootfs/app/libs /rootfs/app/logs /rootfs/usr/share/nginx/html \
    && chown 65532:65532 /rootfs/app/logs /rootfs/var/lib/nginx/logs /rootfs/var/lib/nginx/tmp

# ===========================================================================================================
# 3. Final stage: server + UI in one container
# ===========================================================================================================
# Same contract as before: /bin/sh /app/startup.sh starts nginx (UI on 5000) and the server (8080),
# configured with CONFIG_PROP and JAVA_OPTS. Runs as the non-root Chainguard user (65532).
FROM scratch AS final

LABEL maintainer="Orkes OSS <oss@orkes.io>"

COPY --from=runtime-rootfs /rootfs/ /

# Environment of the Chainguard JRE image (lost with FROM scratch).
ENV JAVA_HOME=/usr/lib/jvm/default-jvm \
    LANG=en_US.UTF-8 \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/bin:/usr/sbin:/sbin:/bin \
    SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt

COPY --chmod=0755 docker/server/bin/startup.sh /app/startup.sh
COPY docker/server/config /app/config
COPY --from=builder /conductor/server/build/libs/*boot*.jar /app/libs/conductor-server.jar

COPY --from=ui-builder /conductor/ui-next/dist /usr/share/nginx/html
COPY docker/server/nginx/nginx.main.conf /etc/nginx/nginx.conf
COPY docker/server/nginx/nginx.conf /etc/nginx/conf.d/default.conf

USER 65532
WORKDIR /app
EXPOSE 8080 5000

# No HEALTHCHECK: Chainguard's busybox has no wget/nc and curl is not installed. Kubernetes ignores
# HEALTHCHECK and uses its own httpGet probes on /health.

ENTRYPOINT ["/bin/sh"]
CMD ["/app/startup.sh"]
