#!/usr/bin/env bash
set -ev

if [ "$TRAVIS_PULL_REQUEST" = "false" ]; then
  echo $GPG_SECRET_KEYS | base64 --decode | $GPG_EXECUTABLE --import
  echo $GPG_OWNERTRUST | base64 --decode | $GPG_EXECUTABLE --import-ownertrust
fi