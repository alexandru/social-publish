FROM node:20-alpine AS frontend-build

WORKDIR /app/frontend

COPY frontend/package*.json ./
RUN npm install

COPY frontend/ .
RUN npm run build

FROM sbtscala/scala-sbt:eclipse-temurin-jammy-17.0.10_7_1.10.7_3.3.4 AS backend-build

WORKDIR /app

COPY backend-scala/project ./project
COPY backend-scala/build.sbt ./build.sbt
RUN sbt -Dsbt.supershell=false update

COPY backend-scala/ .
RUN sbt -Dsbt.supershell=false assembly

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

RUN adduser -u 1001 -h /app -s /bin/sh -D appuser
RUN chown -R appuser /app && chmod -R "g+rwX" /app
RUN mkdir -p /var/lib/social-publish /app/public
RUN chown -R appuser /var/lib/social-publish /app/public && chmod -R "g+rwX" /var/lib/social-publish /app/public

COPY --from=backend-build --chown=appuser:root /app/target/scala-*/social-publish-backend.jar /app/social-publish-backend.jar
COPY --from=frontend-build --chown=appuser:root /app/frontend/dist/. /app/public/
COPY ./scripts/docker-entrypoint.sh /app/docker-entrypoint.sh

EXPOSE 3000
USER appuser

ENV HTTP_PORT=3000
ENV DB_PATH=/var/lib/social-publish/sqlite3.db
ENV UPLOADED_FILES_PATH="/var/lib/social-publish/uploads"
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0"

RUN mkdir -p "${UPLOADED_FILES_PATH}"

ENTRYPOINT [ "./docker-entrypoint.sh", "java", "-jar", "/app/social-publish-backend.jar" ]
