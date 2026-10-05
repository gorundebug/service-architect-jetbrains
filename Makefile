DEPENDENCY_DOCKER_REGISTRY ?= docker.io

.PHONY: docker-build
docker-build:
	docker build \
		--build-arg BUILD_IMAGE=$(DEPENDENCY_DOCKER_REGISTRY)/library/gradle:9.7-jdk21 \
		--output type=local,dest=dist .
