FROM node:20-alpine AS frontend-build

WORKDIR /app/frontend

COPY frontend/package*.json ./
RUN npm install

COPY frontend/ .
RUN npm run build

FROM eclipse-temurin:25-jdk-jammy AS backend-build

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

COPY backend/sbt ./sbt
COPY backend/project ./project
COPY backend/build.sbt ./build.sbt
RUN chmod +x ./sbt
RUN ./sbt -Dsbt.supershell=false update

COPY backend/ .
RUN ./sbt -Dsbt.supershell=false assembly

FROM eclipse-temurin:25-alpine AS jre-build

# Create a custom Java runtime with minimal modules for reduced memory footprint
RUN apk add binutils --no-cache
RUN $JAVA_HOME/bin/jlink \
        --add-modules java.base \
        --add-modules java.xml \
        --add-modules java.naming \
        --add-modules java.management \
        --add-modules java.sql \
        --add-modules java.desktop \
        --add-modules java.net.http \
        --add-modules jdk.unsupported \
        --add-modules jdk.crypto.ec \
        --strip-debug \
        --no-man-pages \
        --no-header-files \
        --compress=2 \
        --output /javaruntime

FROM alpine:3.21

WORKDIR /app

RUN adduser -u 1001 -D -h /app -s /bin/sh appuser
RUN mkdir -p /var/lib/social-publish /app/public
RUN chown -R appuser:appuser /app /var/lib/social-publish && chmod -R "g+rwX" /app /var/lib/social-publish

ENV HTTP_PORT=3000
ENV DB_PATH=/var/lib/social-publish/sqlite3.db
ENV UPLOADED_FILES_PATH="/var/lib/social-publish/uploads"

RUN mkdir -p "${UPLOADED_FILES_PATH}" && chown -R appuser:appuser "${UPLOADED_FILES_PATH}"

# Copy custom JRE
COPY --from=jre-build /javaruntime /opt/java/openjdk

# Copy java-exec script with optimized GC settings
COPY --chown=appuser:appuser ./scripts/java-exec /usr/local/bin/java-exec
RUN chmod +x /usr/local/bin/java-exec

COPY --from=backend-build --chown=appuser:appuser /app/target/scala-*/social-publish-backend.jar /app/social-publish-backend.jar
COPY --from=frontend-build --chown=appuser:appuser /app/frontend/dist/. /app/public/
COPY --chown=appuser:appuser ./scripts/docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

# Set PATH to use custom JRE
ENV PATH=/opt/java/openjdk/bin:$PATH

USER appuser
EXPOSE 3000

ENTRYPOINT [ "./docker-entrypoint.sh", "java-exec", "-jar", "/app/social-publish-backend.jar" ]
