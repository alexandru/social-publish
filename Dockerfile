FROM node:20-alpine AS frontend-build

WORKDIR /app/frontend

COPY frontend/package*.json ./
RUN npm install

COPY frontend/ .
RUN npm run build

FROM eclipse-temurin:21-jdk-jammy AS backend-build

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

COPY backend/sbt ./sbt
COPY backend/project ./project
COPY backend/build.sbt ./build.sbt
RUN chmod +x ./sbt
RUN ./sbt -Dsbt.supershell=false update

COPY backend/ .
RUN ./sbt -Dsbt.supershell=false assembly

FROM eclipse-temurin:21-jdk-jammy

WORKDIR /app

RUN useradd -u 1001 -d /app -s /bin/sh -m appuser
RUN mkdir -p /var/lib/social-publish /app/public
RUN chown -R appuser:appuser /app /var/lib/social-publish && chmod -R "g+rwX" /app /var/lib/social-publish

ENV HTTP_PORT=3000
ENV DB_PATH=/var/lib/social-publish/sqlite3.db
ENV UPLOADED_FILES_PATH="/var/lib/social-publish/uploads"
ENV JAVA_TOOL_OPTIONS="-XX:+UseG1GC -XX:MaxRAMPercentage=60.0 -XX:InitialRAMPercentage=25.0 -XX:+UseStringDeduplication -XX:+ExitOnOutOfMemoryError"

RUN mkdir -p "${UPLOADED_FILES_PATH}" && chown -R appuser:appuser "${UPLOADED_FILES_PATH}"

COPY --from=backend-build --chown=appuser:appuser /app/target/scala-*/social-publish-backend.jar /app/social-publish-backend.jar
COPY --from=frontend-build --chown=appuser:appuser /app/frontend/dist/. /app/public/
COPY --chown=appuser:appuser ./scripts/docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

USER appuser
EXPOSE 3000

ENTRYPOINT [ "./docker-entrypoint.sh", "java", "-jar", "/app/social-publish-backend.jar" ]
