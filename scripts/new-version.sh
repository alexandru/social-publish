#!/bin/sh

if [ -z "$GIT_TAG" ]; then
  echo "sha-$(git log -1 --pretty=%h)"
else
  echo "$GIT_TAG" | cut -d"/" -f 3
fi
