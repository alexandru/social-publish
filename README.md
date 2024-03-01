# social-publish

[![deploy](https://github.com/alexandru/social-publish/actions/workflows/deploy.yaml/badge.svg)](https://github.com/alexandru/social-publish/actions/workflows/deploy.yaml)

In implementing [POSE](https://indieweb.org/POSSE) (publish on your own site, syndicate elsewhere) I need to publish to multiple social networks. I'm using [ifttt.com](https://ifttt.com/), but it doesn't do a good job — their LinkedIn integration is most often broken, and Bluesky integration is currently missing.

This project is the start of a simple tool to publish my content to multiple social networks. For now, only [Bluesky](https://bsky.app/) support is implemented, because this is the urgent need.

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
      - "3000:3000"
    env_file:
      - ./envs/social-publish.env
    networks:
      - external_network
```

Where `./envs/social-publish.env` contains:

```sh
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
```

For Bluesky, you'll need an "app password". Go here to create one:
<https://bsky.app/settings/app-passwords>

For Mastodon, you'll need an "access token". Here's how to get one:
- Go to: <https://mastodon.social/settings/applications>
- Create a "New Application"
- Uncheck everything except "write:statuses"
- Click on the newly created application
- Copy "your access token"

## Usage

The available requests for creating a new post are exemplified in [test.http](./test.http).

You can then configure `ifttt.com`. When adding an "action" to your applet, search for "*make a web request*".

## License

[MIT (see LICENSE.txt)](./LICENSE.txt)
