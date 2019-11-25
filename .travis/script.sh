#!/usr/bin/env bash
set -ev

if [[ -z "${TRAVIS_TAG}" ]]; then
  mvn deploy -Dmaven.deploy.skip=true -B -U -Pdebug
else
  mvn deploy --settings .maven.xml -B -U -Prelease
fi