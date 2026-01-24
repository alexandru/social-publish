NAME          := ghcr.io/alexandru/social-publish
TAG           := $$(./scripts/new-version.sh)
IMG_JVM       := ${NAME}:jvm-${TAG}
LATEST_JVM    := ${NAME}:jvm-latest
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
	-e "TWITTER_OAUTH1_CONSUMER_SECRET=${TWITTER_OAUTH1_CONSUMER_SECRET}" \
	-e "LINKEDIN_CLIENT_ID=${LINKEDIN_CLIENT_ID}" \
	-e "LINKEDIN_CLIENT_SECRET=${LINKEDIN_CLIENT_SECRET}"

# Development targets
dev:
	@trap 'kill 0' INT; \
	JAVA_TOOL_OPTIONS="-Dio.ktor.development=true" ./gradlew :backend:run --args="start-server" & \
	./gradlew :frontend:jsBrowserDevelopmentRun & \
	wait

dev-backend:
	JAVA_TOOL_OPTIONS="-Dio.ktor.development=true" ./gradlew :backend:run --args="start-server"

dev-frontend:
	./gradlew :frontend:jsBrowserDevelopmentRun

# Gradle build targets
build:
	./gradlew build

clean:
	./gradlew clean

test:
	./gradlew test

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
	docker buildx build --platform linux/amd64,linux/arm64 -f ./Dockerfile.jvm -t "${IMG_JVM}" -t "${LATEST_JVM}" ${DOCKER_EXTRA_ARGS} .

push-jvm:
	DOCKER_EXTRA_ARGS="--push" $(MAKE) build-jvm

# Build and push for a single platform (used in matrix builds)
build-jvm-platform: init-docker
	$(eval PLATFORM_TAG := $(shell echo ${PLATFORM} | tr '/' '-'))
	docker buildx build --platform ${PLATFORM} -f ./Dockerfile.jvm -t "${IMG_JVM}-${PLATFORM_TAG}" -t "${LATEST_JVM}-${PLATFORM_TAG}" ${DOCKER_EXTRA_ARGS} .

push-jvm-platform:
	DOCKER_EXTRA_ARGS="--push" $(MAKE) build-jvm-platform

# Create and push multi-platform manifest combining platform-specific images
push-jvm-manifest:
	docker buildx imagetools create -t "${IMG_JVM}" -t "${LATEST_JVM}" -t "${LATEST}" \
		"${IMG_JVM}-linux-amd64" \
		"${IMG_JVM}-linux-arm64"

build-jvm-local:
	docker build -f ./Dockerfile.jvm -t "${IMG_JVM}" -t "${LATEST_JVM}" -t "${LATEST}" .

run-jvm: build-jvm-local
	docker rm -f social-publish || true
	docker run -it -p 3000:3000 --rm --name social-publish ${RUN_ENV_VARS} ${LATEST_JVM}

# Code quality
lint:
	./gradlew ktfmtCheck

format:
	./gradlew ktfmtFormat
