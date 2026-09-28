NAME          := ghcr.io/alexandru/social-publish
TAG           := $$(./scripts/new-version.sh)
IMG_JVM       := ${NAME}:jvm-${TAG}
LATEST_JVM    := ${NAME}:jvm-latest
IMG_NATIVE    := ${NAME}:native-${TAG}
LATEST_NATIVE := ${NAME}:native-latest
LATEST        := ${NAME}:latest
PLATFORM      ?= linux/amd64,linux/arm64

# Environment variables for local runs (from .envrc)
RUN_ENV_VARS := \
	-e "DB_PATH=/var/lib/social-publish/sqlite3.db" \
	-e "HTTP_PORT=3000" \
	-e "BASE_URL=${BASE_URL}" \
	-e "UPLOADED_FILES_PATH=/var/lib/social-publish/uploads"

# Development targets
dev:
	@trap 'kill 0' INT; \
	JAVA_TOOL_OPTIONS="-Dio.ktor.development=true" ./gradlew :backend:run --args="start-server" & \
	./gradlew :frontend:jsBrowserDevelopmentRun --continuous & \
	wait

dev-backend:
	JAVA_TOOL_OPTIONS="-Dio.ktor.development=true" ./gradlew :backend:run --args="start-server"

dev-frontend:
	./gradlew :frontend:jsBrowserDevelopmentRun --continuous

# Gradle build targets
build:
	./gradlew build

clean:
	./gradlew clean

test:
	./gradlew test

native-test:
	./gradlew :backend:nativeTest

# Regenerate native-image reachability metadata: runs the test suite with
# the GraalVM tracing agent (requires a GraalVM install); output lands in
# backend/build/native/agent-output
generate-native-metadata:
	./gradlew test -PnativeAgent=true

dependency-updates:
	./gradlew dependencyUpdates \
		-Drevision=release \
		-DoutputFormatter=html \
		--refresh-dependencies && \
		open backend/build/dependencyUpdates/report.html && \
		open frontend/build/dependencyUpdates/report.html

dependency-updates-ci:
	./gradlew dependencyUpdates --no-parallel -Drevision=release -DoutputFormatter=html --refresh-dependencies

skills-update:
	npx skills add alexandru/skills -a claude-code github-copilot opencode -y --skill \
		arrow-resource \
		arrow-typed-errors \
		compose-state-hoisting \
		kotlin-context-parameters \
		simplify

# Docker setup
docker-init:
	docker buildx inspect mybuilder || docker buildx create --name mybuilder
	docker buildx use mybuilder

# JVM Docker targets
docker-build-jvm: docker-init
	docker buildx build --platform linux/amd64,linux/arm64 -f ./docker/Dockerfile.jvm -t "${IMG_JVM}" -t "${LATEST_JVM}" ${DOCKER_EXTRA_ARGS} .

docker-push-jvm:
	DOCKER_EXTRA_ARGS="--push" $(MAKE) docker-build-jvm

# Build and push for a single platform (used in matrix builds)
docker-build-jvm-platform: docker-init
	$(eval PLATFORM_TAG := $(shell echo ${PLATFORM} | tr '/' '-'))
	docker buildx build --platform ${PLATFORM} -f ./docker/Dockerfile.jvm -t "${IMG_JVM}-${PLATFORM_TAG}" -t "${LATEST_JVM}-${PLATFORM_TAG}" ${DOCKER_EXTRA_ARGS} .

docker-push-jvm-platform:
	DOCKER_EXTRA_ARGS="--push" $(MAKE) docker-build-jvm-platform

# Create and push multi-platform manifest combining platform-specific images
docker-push-jvm-manifest:
	docker buildx imagetools create -t "${IMG_JVM}" -t "${LATEST_JVM}" -t "${LATEST}" \
		"${IMG_JVM}-linux-amd64" \
		"${IMG_JVM}-linux-arm64"

docker-build-jvm-local:
	docker build -f ./docker/Dockerfile.jvm -t "${IMG_JVM}" -t "${LATEST_JVM}" -t "${LATEST}" .

docker-run-jvm: docker-build-jvm-local
	docker rm -f social-publish || true
	docker run -it -p 3000:3000 --rm --name social-publish -v social-publish-data:/var/lib/social-publish ${RUN_ENV_VARS} ${LATEST_JVM}

# Native Docker targets
docker-build-native: docker-init
	docker buildx build --platform linux/amd64,linux/arm64 -f ./docker/Dockerfile.native -t "${IMG_NATIVE}" -t "${LATEST_NATIVE}" ${DOCKER_EXTRA_ARGS} .

docker-push-native:
	DOCKER_EXTRA_ARGS="--push" $(MAKE) docker-build-native

docker-build-native-platform: docker-init
	$(eval PLATFORM_TAG := $(shell echo ${PLATFORM} | tr '/' '-'))
	docker buildx build --platform ${PLATFORM} -f ./docker/Dockerfile.native -t "${IMG_NATIVE}-${PLATFORM_TAG}" -t "${LATEST_NATIVE}-${PLATFORM_TAG}" ${DOCKER_EXTRA_ARGS} .

docker-push-native-platform:
	DOCKER_EXTRA_ARGS="--push" $(MAKE) docker-build-native-platform

docker-push-native-manifest:
	docker buildx imagetools create -t "${IMG_NATIVE}" -t "${LATEST_NATIVE}" \
		"${IMG_NATIVE}-linux-amd64" \
		"${IMG_NATIVE}-linux-arm64"

docker-build-native-local:
	docker build -f ./docker/Dockerfile.native -t "${IMG_NATIVE}" -t "${LATEST_NATIVE}" .

docker-run-native: docker-build-native-local
	docker rm -f social-publish || true
	docker run -it -p 3000:3000 --rm --name social-publish -v social-publish-data:/var/lib/social-publish ${RUN_ENV_VARS} ${LATEST_NATIVE}

# Code quality
lint:
	./gradlew ktfmtCheck

format:
	./gradlew ktfmtFormat

# Docker test targets
docker-build-tests:
	docker build -f ./docker/Dockerfile.run-tests -t social-publish-tests:latest .

docker-run-tests: docker-build-tests
	docker run --rm social-publish-tests:latest ./gradlew test --no-daemon

docker-run-tests-imagemagick: docker-build-tests
	docker run --rm social-publish-tests:latest ./gradlew :backend:test --tests "ImageMagickTest" --no-daemon
