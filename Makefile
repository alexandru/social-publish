NAME          := ghcr.io/alexandru/social-publish
TAG           := $$(./scripts/new-version.sh)
IMG_JVM       := ${NAME}:jvm-${TAG}
IMG_NATIVE    := ${NAME}:native-${TAG}
LATEST_JVM    := ${NAME}:jvm-latest
LATEST_NATIVE := ${NAME}:native-latest
LATEST        := ${NAME}:latest
PLATFORM      ?= linux/amd64,linux/arm64

# Environment variables for local runs (from .envrc)
RUN_ENV_VARS := \
	-e "DB_PATH=/var/lib/social-publish/sqlite3.db" \
	-e "HTTP_PORT=3000" \
	-e "BASE_URL=${BASE_URL}" \
	-e "SERVER_AUTH_USERNAME=${SERVER_AUTH_USERNAME}" \
	-e "SERVER_AUTH_PASSWORD=${SERVER_AUTH_PASSWORD}" \
	-e "JWT_SECRET=${JWT_SECRET}" \
	-e "UPLOADED_FILES_PATH=/var/lib/social-publish/uploads" \
	-e "BSKY_SERVICE=${BSKY_SERVICE}" \
	-e "BSKY_USERNAME=${BSKY_USERNAME}" \
	-e "BSKY_PASSWORD=${BSKY_PASSWORD}" \
	-e "MASTODON_HOST=${MASTODON_HOST}" \
	-e "MASTODON_ACCESS_TOKEN=${MASTODON_ACCESS_TOKEN}" \
	-e "TWITTER_OAUTH1_CONSUMER_KEY=${TWITTER_OAUTH1_CONSUMER_KEY}" \
	-e "TWITTER_OAUTH1_CONSUMER_SECRET=${TWITTER_OAUTH1_CONSUMER_SECRET}"

# Development targets
dev:
	@trap 'kill 0' INT; \
	./gradlew :backend:run --args="start-server" --continuous & \
	./gradlew :frontend:jsBrowserDevelopmentRun --continuous & \
	wait

dev-backend:
	./gradlew :backend:run --args="start-server"

dev-frontend:
	./gradlew :frontend:jsBrowserDevelopmentRun --continuous

# Gradle build targets
build:
	./gradlew build

clean:
	./gradlew clean

test:
	./gradlew test

test-native:
	./gradlew :backend:nativeTest

# Collect native-image metadata by running tests with GraalVM agent
collect-native-metadata:
	./gradlew :backend:collectNativeMetadata

# Dependency updates
dependency-updates:
	./gradlew dependencyUpdates \
		-Drevision=release \
		-DoutputFormatter=html \
		--refresh-dependencies && \
		open backend/build/dependencyUpdates/report.html &&
		open frontend/build/dependencyUpdates/report.html

# Docker setup
init-docker:
	docker buildx inspect mybuilder || docker buildx create --name mybuilder
	docker buildx use mybuilder

# JVM Docker targets
build-jvm: init-docker
	docker buildx build --platform linux/amd64,linux/arm64 -f docker/Dockerfile.jvm -t "${IMG_JVM}" -t "${LATEST_JVM}" ${DOCKER_EXTRA_ARGS} .

push-jvm:
	DOCKER_EXTRA_ARGS="--push" $(MAKE) build-jvm

# Build and push for a single platform (used in matrix builds)
build-jvm-platform: init-docker
	$(eval PLATFORM_TAG := $(shell echo ${PLATFORM} | tr '/' '-'))
	docker buildx build --platform ${PLATFORM} -f docker/Dockerfile.jvm -t "${IMG_JVM}-${PLATFORM_TAG}" -t "${LATEST_JVM}-${PLATFORM_TAG}" ${DOCKER_EXTRA_ARGS} .

push-jvm-platform:
	DOCKER_EXTRA_ARGS="--push" $(MAKE) build-jvm-platform

# Create and push multi-platform manifest combining platform-specific images
push-jvm-manifest:
	docker buildx imagetools create -t "${IMG_JVM}" -t "${LATEST_JVM}" -t "${LATEST}" \
		"${IMG_JVM}-linux-amd64" \
		"${IMG_JVM}-linux-arm64"

build-jvm-local:
	docker build -f docker/Dockerfile.jvm -t "${IMG_JVM}" -t "${LATEST_JVM}" -t "${LATEST}" .

run-jvm: build-jvm-local
	docker rm -f social-publish || true
	docker run -it -p 3000:3000 --rm --name social-publish ${RUN_ENV_VARS} ${LATEST_JVM}

# Native Docker targets
build-native: init-docker
	docker buildx build --platform linux/amd64,linux/arm64 -f docker/Dockerfile.native -t "${IMG_NATIVE}" -t "${LATEST_NATIVE}" ${DOCKER_EXTRA_ARGS} .

push-native:
	DOCKER_EXTRA_ARGS="--push" $(MAKE) build-native

# Build and push for a single platform (used in matrix builds)
build-native-platform: init-docker
	$(eval PLATFORM_TAG := $(shell echo ${PLATFORM} | tr '/' '-'))
	docker buildx build --platform ${PLATFORM} -f docker/Dockerfile.native -t "${IMG_NATIVE}-${PLATFORM_TAG}" -t "${LATEST_NATIVE}-${PLATFORM_TAG}" ${DOCKER_EXTRA_ARGS} .

push-native-platform:
	DOCKER_EXTRA_ARGS="--push" $(MAKE) build-native-platform

# Create and push multi-platform manifest combining platform-specific images
push-native-manifest:
	docker buildx imagetools create -t "${IMG_NATIVE}" -t "${LATEST_NATIVE}" \
		"${IMG_NATIVE}-linux-amd64" \
		"${IMG_NATIVE}-linux-arm64"

build-native-local:
	docker build -f docker/Dockerfile.native -t "${IMG_NATIVE}" -t "${LATEST_NATIVE}" .

run-native: build-native-local
	docker rm -f social-publish || true
	docker run -it -p 3000:3000 --rm --name social-publish ${RUN_ENV_VARS} ${LATEST_NATIVE}

# Code quality
lint:
	./gradlew ktfmtCheck

format:
	./gradlew ktfmtFormat
