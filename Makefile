NAME          := ghcr.io/alexandru/social-publish
VERSION       := $$(./scripts/new-version.sh)
VERSION_TAG   := ${NAME}:${VERSION}
LATEST_TAG    := ${NAME}:latest
DOCKERFILE    ?= ./Dockerfile.jvm

init-docker:
	docker buildx inspect mybuilder || docker buildx create --name mybuilder
	docker buildx use mybuilder

build-production: init-docker
	docker buildx build --platform linux/amd64,linux/arm64 -f ${DOCKERFILE} -t "${LATEST_TAG}" ${DOCKER_EXTRA_ARGS} .

push-production-latest:
	DOCKER_EXTRA_ARGS="--push" $(MAKE) build-production

push-production-release:
	DOCKER_EXTRA_ARGS="-t '${VERSION_TAG}' --push" $(MAKE) build-production

build-local:
	docker build -f ${DOCKERFILE} -t "${VERSION_TAG}" -t "${LATEST_TAG}" .

build-local-native:
	DOCKERFILE=./Dockerfile.native $(MAKE) build-local

build-production-native:
	DOCKERFILE=./Dockerfile.native $(MAKE) build-production

# Shared recipe for running local docker containers
define run_local_docker
	docker rm -f social-publish || true
	docker run -it -p 3000:3000 \
		--rm \
		--name social-publish \
		-e "BASE_URL=${BASE_URL}" \
		-e "JWT_SECRET=${JWT_SECRET}" \
		-e "BSKY_ENABLED=${BSKY_ENABLED}" \
		-e "BSKY_SERVICE=${BSKY_SERVICE}" \
		-e "BSKY_USERNAME=${BSKY_USERNAME}" \
		-e "BSKY_PASSWORD=${BSKY_PASSWORD}" \
		-e "SERVER_AUTH_USERNAME=${SERVER_AUTH_USERNAME}" \
		-e "SERVER_AUTH_PASSWORD=${SERVER_AUTH_PASSWORD}" \
		-e "MASTODON_ENABLED=${MASTODON_ENABLED}" \
		-e "MASTODON_HOST=${MASTODON_HOST}" \
		-e "MASTODON_ACCESS_TOKEN=${MASTODON_ACCESS_TOKEN}" \
		-e "TWITTER_ENABLED=${TWITTER_ENABLED}" \
		-e "TWITTER_OAUTH1_CONSUMER_KEY=${TWITTER_OAUTH1_CONSUMER_KEY}" \
		-e "TWITTER_OAUTH1_CONSUMER_SECRET=${TWITTER_OAUTH1_CONSUMER_SECRET}" \
		${LATEST_TAG}
endef

run-local: build-local
	$(call run_local_docker)

run-local-native: build-local-native
	$(call run_local_docker)

update:
	npx npm-check-updates -u && npm install && \
	cd ./frontend && npx npm-check-updates -u && npm install && cd .. && \
	./sbt backend/update
