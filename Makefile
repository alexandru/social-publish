NAME          := ghcr.io/alexandru/social-publish
VERSION       := $$(./scripts/new-version.sh)
VERSION_TAG   := ${NAME}:${VERSION}
LATEST_TAG    := ${NAME}:latest

init-docker:
	docker buildx inspect mybuilder || docker buildx create --name mybuilder
	docker buildx use mybuilder

build-production: init-docker
	docker buildx build --platform linux/amd64,linux/arm64 -f ./Dockerfile.jvm -t "${LATEST_TAG}" ${DOCKER_EXTRA_ARGS} .

push-production-latest:
	DOCKER_EXTRA_ARGS="--push" $(MAKE) build-production

push-production-release:
	DOCKER_EXTRA_ARGS="-t '${VERSION_TAG}' --push" $(MAKE) build-production

build-local:
	docker build -f ./Dockerfile.jvm -t "${VERSION_TAG}" -t "${LATEST_TAG}" .

build-native:
	docker build -f ./Dockerfile.native -t "${VERSION_TAG}-native" -t "${LATEST_TAG}-native" .

run-local: build-local
	docker rm -f social-publish || true
	docker run -it -p 3000:3000 \
		--rm \
		--name social-publish \
		-e "BASE_URL=${BASE_URL}" \
		-e "JWT_SECRET=${JWT_SECRET}" \
		-e "BSKY_HOST=${BSKY_HOST}" \
		-e "BSKY_USERNAME=${BSKY_USERNAME}" \
		-e "BSKY_PASSWORD=${BSKY_PASSWORD}" \
		-e "SERVER_AUTH_USERNAME=${SERVER_AUTH_USERNAME}" \
		-e "SERVER_AUTH_PASSWORD=${SERVER_AUTH_PASSWORD}" \
		-e "MASTODON_HOST=${MASTODON_HOST}" \
		-e "MASTODON_ACCESS_TOKEN=${MASTODON_ACCESS_TOKEN}" \
		-e "TWITTER_OAUTH1_CONSUMER_KEY=${TWITTER_OAUTH1_CONSUMER_KEY}" \
		-e "TWITTER_OAUTH1_CONSUMER_SECRET=${TWITTER_OAUTH1_CONSUMER_SECRET}" \
		${LATEST_TAG}

# Development targets
dev:
	./gradlew :backend-kotlin:run & ./gradlew :frontend-compose:jsBrowserDevelopmentRun --continuous

dev-backend:
	./gradlew :backend-kotlin:run

dev-frontend:
	./gradlew :frontend-compose:jsBrowserDevelopmentRun --continuous

dev-compose:
	./gradlew :backend-kotlin:run & ./gradlew :frontend-compose:jsBrowserDevelopmentRun --continuous

dev-frontend-compose:
	./gradlew :frontend-compose:jsBrowserDevelopmentRun --continuous

# Build targets
build:
	./gradlew build

clean:
	./gradlew clean

# Dependency updates
update:
	./gradlew dependencyUpdates

# Legacy Node.js targets (kept for backwards compatibility with ./frontend and ./backend)
dev-legacy:
	npm run dev

build-legacy:
	npm run build

# Testing
test:
	./gradlew test

# Code quality
lint:
	./gradlew ktlintCheck

format:
	./gradlew ktlintFormat
