ARG WOLFI_BASE=cgr.dev/chainguard/wolfi-base:latest

FROM ${WOLFI_BASE}

# The base image's default apk repo (apk.cgr.dev) requires Chainguard auth; use the public Wolfi repo
ARG WOLFI_APK_REPO=https://packages.wolfi.dev/os
RUN echo "${WOLFI_APK_REPO}" > /etc/apk/repositories

# The Gradle toolchain targets Java 21; libstdc++ is needed by the native protoc executables
RUN apk add --no-cache openjdk-21 openjdk-21-default-jdk bash git libstdc++
ENV JAVA_HOME=/usr/lib/jvm/default-jvm

WORKDIR /workspace/conductor
COPY . /workspace/conductor

RUN ./gradlew clean build
