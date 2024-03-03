# Use an official Node.js runtime as the base image
FROM alpine:latest as build

# Set the working directory in the container to /app
WORKDIR /app

# Copy package.json and package-lock.json to the working directory
COPY package*.json ./

# Install required packages
RUN apk add --no-cache nodejs npm

# Install the application dependencies
RUN npm install

# Copy the rest of the application code to the working directory
COPY . .

# Build the application
RUN npm run build
# Cleanup extraneous node modules
RUN rm -r node_modules/
RUN npm install --omit=dev
RUN apk del npm

RUN adduser -u 1001 -h /app -s /bin/sh -D appuser
RUN chown -R appuser /app && chmod -R "g+rwX" /app
RUN mkdir /var/lib/social-publish
RUN chown -R appuser /var/lib/social-publish && chmod -R "g+rwX" /var/lib/social-publish

# Expose port 3000 for the application
EXPOSE 3000
USER appuser

ENV NODE_ENV=production
ENV PORT=3000
ENV DB_PATH=/var/lib/social-publish/sqlite3.db

# Define the command to run the application
ENTRYPOINT [ "./scripts/docker-entrypoint.sh", "node", "./build/server.js" ]
