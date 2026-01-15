#!/bin/sh

cd "$(dirname "$0")/.." || exit 1

rm -rf dist
mkdir -p dist/public

cp -rf ./frontend/dist/. dist/public || exit 1
cp ./backend-scala/target/scala-*/social-publish-backend.jar dist/ || exit 1
