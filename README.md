# social-publish

[![build](https://github.com/alexandru/social-publish/actions/workflows/build.yaml/badge.svg)](https://github.com/alexandru/social-publish/actions/workflows/build.yaml) [![deploy](https://github.com/alexandru/social-publish/actions/workflows/deploy.yml/badge.svg)](https://github.com/alexandru/social-publish/actions/workflows/deploy.yml)

In implementing [POSSE](https://indieweb.org/POSSE) (publish on your own site, syndicate elsewhere) I need to publish to multiple social networks. This project provides direct API integration for X (Twitter), Mastodon, Bluesky, and LinkedIn.

This project is a simple tool to publish content to multiple social networks.

- [X (Twitter)](https://twitter.com), [Mastodon](https://joinmastodon.org/), [Bluesky](https://bsky.app/), and [LinkedIn](https://linkedin.com) support is implemented
- Image upload is supported, with alt-text included 😊
- Also exports an RSS feed, meant for automation via `ifttt.com`

**Table of Contents**

- [Self-hosting](#self-hosting)
  - [Bluesky credentials](#bluesky-credentials)
  - [Mastodon credentials](#mastodon-credentials)
  - [Twitter setup](#twitter-setup)
  - [LinkedIn setup](#linkedin-setup)
- [Usage](#usage)
  - [RSS feed](#rss-feed)
- [Developing](#developing)
- [License](#license)

## Self-hosting

My `docker-compose` setup:

```yaml
version: "3.8"

services:
  # ...
  social-publish:
    container_name: social-publish
    image: ghcr.io/alexandru/social-publish:latest
    restart: always
    healthcheck:
      test: ["CMD-SHELL", "curl --head http://localhost:3000/ || exit 1"]
    ports:
      - "3000:3000"
    env_file:
      - ./envs/social-publish.env
    networks:
      - external_network
```

Where `./envs/social-publish.env` contains:

```sh
# Where the server is hosted — needed for correctly generating an RSS feed
BASE_URL="https://your-hostname.com"

# The server's Basic AUTH credentials
SERVER_AUTH_USERNAME="your-username"
SERVER_AUTH_PASSWORD="your-password"

# Bluesky credentials
BSKY_HOST="https://bsky.social"
BSKY_USERNAME="your-username"
BSKY_PASSWORD="your-password"

# Mastodon credentials
MASTODON_HOST="https://mastodon.social"
MASTODON_ACCESS_TOKEN="your-access-token"

# YouTube Oauth1 key and secret (Consumer Keys in the Developer Portal)
TWITTER_OAUTH1_CONSUMER_KEY="Api Key"
TWITTER_OAUTH1_CONSUMER_SECRET="Api Secret Key"

# LinkedIn OAuth2 credentials
LINKEDIN_CLIENT_ID="your-client-id"
LINKEDIN_CLIENT_SECRET="your-client-secret"

# Used for authentication (https://jwt.io)
JWT_SECRET="random string"
```

### Bluesky credentials

For Bluesky, you'll need an "app password".

- Go here to create one: <https://bsky.app/settings/app-passwords>
- Copy the password
- Set the `BSKY_PASSWORD` environment variable to it

Keep it safe, as it grants access to everything.

### Mastodon credentials

For Mastodon, you'll need an "access token". Here's how to get one:

- Go to: <https://mastodon.social/settings/applications>
- Create a "New Application"
- Select `write:statuses` and `write:media` for permissions, and unselect everything else
- Click on the newly created application
- Copy "_your access token_"
- Set the `MASTODON_ACCESS_TOKEN` environment variable to it

### Twitter setup

For Twitter, we're working with Oauth1.

- Go to: <https://developer.twitter.com/en/portal/projects-and-apps>
- Create a project and app
- In the "_Keys and tokens_" section of the app, generate "_Consumer Keys_" and copy the generated "_App Key and Secret_"
- In the app's settings, go to "_User authentication settings_" and add as the "_Callback URL_": `https://<your-domain.com>/api/twitter/callback` (replace `<your-domain.com>` with your domain, obviously)
- Set the `TWITTER_OAUTH1_CONSUMER_KEY` and the `TWITTER_OAUTH1_CONSUMER_SECRET` environment variables
- Once the server is running, go to `https://<your-domain.com>/account` and click on "_Connect Twitter_"

### LinkedIn setup

For LinkedIn, we're working with OAuth2.

- Go to: <https://www.linkedin.com/developers/apps>
- Click "_Create app_" and fill in the required details
- In the "_Auth_" tab, copy the "_Client ID_" and "_Client Secret_"
- Add the following redirect URL: `https://<your-domain.com>/api/linkedin/callback` (replace `<your-domain.com>` with your actual domain)
- In the "_Products_" tab, request access to "_Share on LinkedIn_" (this provides the `w_member_social` scope)
- Set the `LINKEDIN_CLIENT_ID` and `LINKEDIN_CLIENT_SECRET` environment variables
- Once the server is running, go to `https://<your-domain.com>/account` and click on "_Connect LinkedIn_"

**Note:** LinkedIn access tokens expire after 60 days. The system automatically refreshes tokens using the refresh token, which is valid for 1 year. You'll need to reconnect if the refresh token expires.

## Usage

The available requests for creating a new post are exemplified in [test.http](./test.http).

You can then configure `ifttt.com`. When adding an "action" to your applet, search for "_make a web request_".

Or, if you open the webpage in a browser (e.g., `http://localhost:3000/`), you can use this form:

<img src="./docs/form-20240310.png" width="500" alt='Screenshot of "Post a New Social Message" form' />
<hr/>

### RSS feed

While this service is able to publish directly to Mastodon and Bluesky, for other social networks you can use the RSS feed, exposed at `/rss` (e.g., `http://localhost:3000/rss`) in combination with [ifttt.com](https://ifttt.com).

## Developing

This is a Kotlin multiplatform project with:

- **Backend**: Ktor server with Arrow for functional programming
- **Frontend**: Compose for Web (Kotlin/JS)
- **Build**: Gradle with Kotlin DSL

### Development Commands

To run the development environment with live reload:

```sh
make dev
```

This starts both the backend server (port 3000) and frontend dev server (port 3001) with hot reload enabled.

To run backend and frontend separately:

```sh
# Backend only
make dev-backend

# Frontend only
make dev-frontend
```

You can navigate to <http://localhost:3001> for the frontend, while the backend is available at <http://localhost:3000>.

### Building

To build the project:

```sh
make build
```

To run tests:

```sh
make test
```

To check and fix code formatting:

```sh
make lint    # Check formatting
make format  # Auto-format code
```

### Docker Images

To build and test the Docker images locally:

```sh
# Build and run JVM image (smaller build time, larger image)
make run-jvm

# Or build and run native image (longer build time, smaller image)
make run-native
```

See the [Makefile](./Makefile) for all available commands.

## License

[MIT (see LICENSE.txt)](./LICENSE.txt)
