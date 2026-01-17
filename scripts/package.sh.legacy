#!/bin/sh

cd "$(dirname "$0")/.." || exit 1

rm -rf dist
mkdir dist

cp -rf ./backend/build dist/server || exit 1
cp -rf ./frontend/dist dist/public || exit 1
cp ./backend/package.json dist/package.json || exit 1

npm --prefix dist install --production || exit 1
