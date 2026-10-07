DEPENDENCY_DOCKER_REGISTRY ?= docker.io
SA_DSL_SOURCE ?= ../sa-python-dsl

.PHONY: docker-build
docker-build:
	docker build \
		--build-arg BUILD_IMAGE=$(DEPENDENCY_DOCKER_REGISTRY)/library/gradle:9.7-jdk21 \
		--build-context "dsl=$(SA_DSL_SOURCE)" \
		--output type=local,dest=dist .
