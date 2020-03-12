if ( [ "${TRAVIS_PULL_REQUEST}" = "false" ] && [ "${$TRAVIS_BRANCH}" = "dev" ] ) then;
  mvn deploy --settings .maven.xml -B -U -Prelease
fi