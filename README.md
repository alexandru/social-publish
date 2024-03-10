# social-publish

[![deploy-latest](https://github.com/alexandru/social-publish/actions/workflows/deploy-latest.yaml/badge.svg)](https://github.com/alexandru/social-publish/actions/workflows/deploy-latest.yaml) [![deploy-release](https://github.com/alexandru/social-publish/actions/workflows/deploy-release.yaml/badge.svg)](https://github.com/alexandru/social-publish/actions/workflows/deploy-release.yaml)

In implementing [POSE](https://indieweb.org/POSSE) (publish on your own site, syndicate elsewhere) I need to publish to multiple social networks. I'm using [ifttt.com](https://ifttt.com/), but it doesn't do a good job — their LinkedIn integration is most often broken, and Bluesky integration is currently missing.

This project is the start of a simple tool to publish my content to multiple social networks.

- [X (Twitter)](https://twitter.com), [Mastodon](https://joinmastodon.org/), and [Bluesky](https://bsky.app/) support is implemented, LinkedIn is planned
- Image upload is supported, with alt-text included 😊
- Also exports an RSS feed, meant for automation via `ifttt.com`

**Table of Contents**

- [Self-hosting](#self-hosting)
  - [Bluesky credentials](#bluesky-credentials)
  - [Mastodon credentials](#mastodon-credentials)
  - [Twitter setup](#twitter-setup)
- [Usage](#usage)
  - [RSS feed](#rss-feed)
- [Developing](#developing)
- [License](#license)

## Self-hosting

My `docker-compose` setup:

```yaml
version: '3.8'

services:
  # ...
  social-publish:
    container_name: social-publish
    image: ghcr.io/alexandru/social-publish:latest
    restart: always
    healthcheck:
      test: ['CMD-SHELL', 'curl --head http://localhost:3000/ || exit 1']
    ports:
      - '3000:3000'
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

## Usage

The available requests for creating a new post are exemplified in [test.http](./test.http).

You can then configure `ifttt.com`. When adding an "action" to your applet, search for "_make a web request_".

Or, if you open the webpage in a browser (e.g., `http://localhost:3000/`), you can use this form:

<img src="./docs/form-20240307.png" width="500" alt='Screenshot of "Post a New Social Message" form' />
<hr/>

### RSS feed

While this service is able to publish directly to Mastodon and Bluesky, for other social networks you can use the RSS feed, exposed at `/rss` (e.g., `http://localhost:3000/rss`) in combination with [ifttt.com](https://ifttt.com).

## Developing

To start the development server (with incremental compilation, powered by [Vite](https://vitejs.dev/)):

```sh
npm run dev
```

You can then navigate to <http://localhost:3001> for the frontend, while the backend is available at <http://localhost:3000>.

To build and test the Docker image locally:

```sh
make run-local
```

See the [Makefile](./Makefile) for more commands.

## License

[MIT (see LICENSE.txt)](./LICENSE.txt)
