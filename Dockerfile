# Use an official Node.js runtime as the base image
FROM alpine:3.23 AS build

# Set the working directory in the container to /app
WORKDIR /app
RUN mkdir -p /app/frontend && mkdir -p /app/backend

# Copy package.json and package-lock.json to the working directory
COPY package*.json ./
COPY frontend/package*.json ./frontend/
COPY backend/package*.json ./backend/

# Install required packages including build dependencies for native modules
RUN apk add --no-cache nodejs npm python3 py3-setuptools make g++

# Install the application dependencies
RUN npm install && npm run init

# Copy the rest of the application code to the working directory
COPY . .

# Build the application
RUN npm run build
RUN ./scripts/package.sh

###
FROM alpine:3.23

WORKDIR /app

RUN apk add --no-cache nodejs npm

RUN adduser -u 1001 -h /app -s /bin/sh -D appuser
RUN chown -R appuser /app && chmod -R "g+rwX" /app
RUN mkdir -p /var/lib/social-publish
RUN chown -R appuser /var/lib/social-publish && chmod -R "g+rwX" /var/lib/social-publish

COPY --from=build --chown=appuser:root /app/dist/. /app/
COPY ./scripts/docker-entrypoint.sh /app/docker-entrypoint.sh

# Expose port 3000 for the application
EXPOSE 3000
USER appuser

ENV NODE_ENV=production
ENV PORT=3000
ENV DB_PATH=/var/lib/social-publish/sqlite3.db
ENV UPLOADED_FILES_PATH="/var/lib/social-publish/uploads"

RUN mkdir -p "${UPLOADED_FILES_PATH}"

# Define the command to run the application
ENTRYPOINT [ "./docker-entrypoint.sh", "node", "--enable-source-maps", "./server/main.js" ]
