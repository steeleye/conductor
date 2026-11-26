#
# conductor:ui - Conductor UI (ui-next)
#
# Standalone UI image: builds ui-next and serves the static bundle with nginx.
# API requests are proxied to the conductor server; point the container at
# your server with:
#
#   docker run -e WF_SERVER=http://my-conductor:8080 -p 5000:5000 conductor:ui
#
# Build from the repo root:
#
#   docker build -t conductor:ui -f docker/ui/Dockerfile .
#
# The server image (docker/server/Dockerfile) no longer bundles the UI, so this image serves it.
# Runs on Chainguard's nginx (non-root, no shell). That image cannot fill in a config template at
# start-up, so the upstream server address is set at build time:
#
#   docker build --build-arg WF_SERVER=http://127.0.0.1:8080 -f docker/ui/Dockerfile .
#
# The default (127.0.0.1:8080) fits running this container next to the server in the same pod.
#
ARG UI_RUNTIME_BASE=cgr.dev/regsensus.com/nginx:latest

# ===========================================================================================================
# 0. Build stage
# ===========================================================================================================
FROM node:22 AS builder
LABEL maintainer="Orkes OSS <oss@orkes.io>"

COPY ui-next /conductor/ui-next
WORKDIR /conductor/ui-next

RUN corepack enable && \
    pnpm install --frozen-lockfile && \
    NODE_OPTIONS=--max-old-space-size=4096 pnpm build

# Fill in the server address at build time (the runtime image has no envsubst/shell).
ARG WF_SERVER=http://127.0.0.1:8080
COPY docker/ui/nginx.conf.template /tmp/nginx.conf.template
RUN sed "s#\${WF_SERVER}#${WF_SERVER}#g" /tmp/nginx.conf.template > /tmp/conductor-ui.conf \
    && ! grep -q 'WF_SERVER' /tmp/conductor-ui.conf

# ===========================================================================================================
# 1. Serve stage
# ===========================================================================================================
FROM ${UI_RUNTIME_BASE}
LABEL maintainer="Orkes OSS <oss@orkes.io>"

COPY --from=builder /conductor/ui-next/dist /usr/share/nginx/html
# Replace the base image's default site (it listens on 8080, the server's port) with the UI on 5000.
COPY --from=builder /tmp/conductor-ui.conf /etc/nginx/conf.d/nginx.default.conf

EXPOSE 5000
