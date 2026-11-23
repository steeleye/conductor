ARG WOLFI_BASE=cgr.dev/chainguard/wolfi-base:latest

FROM ${WOLFI_BASE}

# The Gradle toolchain targets Java 21
RUN apk add --no-cache openjdk-21 openjdk-21-default-jvm bash git
ENV JAVA_HOME=/usr/lib/jvm/default-jvm

WORKDIR /workspace/conductor
COPY . /workspace/conductor

RUN ./gradlew clean build
