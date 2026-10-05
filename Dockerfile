# syntax=docker/dockerfile:1.7
ARG BUILD_IMAGE=docker.io/library/gradle:9.7-jdk21
FROM ${BUILD_IMAGE} AS build

WORKDIR /home/gradle/project
COPY --chown=gradle:gradle . .
RUN --mount=type=cache,target=/home/gradle/.gradle,uid=1000,gid=1000,sharing=locked \
    gradle --no-daemon --console=plain test buildPlugin

FROM scratch
COPY --from=build /home/gradle/project/build/distributions/ /
