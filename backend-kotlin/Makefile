SHELL := /bin/bash
GRADLE := ./gradlew

.PHONY: help build test-watch format start clean fat-jar

help:
	@echo "Makefile targets:"
	@echo "  build       - compile the main Kotlin sources (no tests)"
	@echo "  test-watch  - run tests continuously on file changes"
	@echo "  format      - run ktlintFormat to format code"
	@echo "  start       - start the server via Gradle run"
	@echo "  clean       - clean build artifacts"
	@echo "  fat-jar     - build an executable fat JAR (Gradle task: fatJar)"

# Compile main Kotlin sources only (no tests)
build:
	$(GRADLE) compileKotlin

# Continuous testing: re-runs tests when inputs change
# Note: Gradle's --continuous flag watches inputs and reruns tasks on change
test-watch:
	$(GRADLE) test --continuous

# Format code using ktlintFormat task
format:
	$(GRADLE) ktlintFormat

# Start the server (delegates to the Gradle 'run' task)
start:
	$(GRADLE) run --no-daemon

# Clean build artifacts
clean:
	$(GRADLE) clean

# Build a fat JAR (includes runtime dependencies)
fat-jar:
	$(GRADLE) fatJar
