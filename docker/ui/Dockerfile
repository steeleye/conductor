#
# conductor:ui - Conductor UI
#
ARG WOLFI_BASE=cgr.dev/chainguard/wolfi-base:latest

FROM ${WOLFI_BASE}
LABEL maintainer="Orkes OSS <oss@orkes.io>"

# The base image's default apk repo (apk.cgr.dev) requires Chainguard auth; use the public Wolfi repo
ARG WOLFI_APK_REPO=https://packages.wolfi.dev/os
RUN echo "${WOLFI_APK_REPO}" > /etc/apk/repositories

# Node 20 (same major as before), corepack for yarn, and the toolchain for native modules
RUN apk add --no-cache nodejs-20 npm corepack python3 py3-pip make build-base \
    && corepack enable

# A directory within the virtualized Docker environment
# Becomes more relevant when using Docker Compose later
WORKDIR /usr/src/app

# Copies package.json to Docker environment in a separate layer as a performance optimization
COPY ./ui/package.json ./

# Installs all node packages. Cached unless package.json changes
RUN yarn install && mkdir -p public && cp -r node_modules/monaco-editor public/

# Copies everything else over to Docker environment
# node_modules excluded in .dockerignore.
COPY ./ui .

# Include monaco sources into bundle (instead of using CDN)
ENV REACT_APP_MONACO_EDITOR_USING_CDN=false
ENV REACT_APP_ENABLE_ERRORS_INSPECTOR=true
CMD [ "yarn", "start" ]
