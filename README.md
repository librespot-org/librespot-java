# librespot-java
[![Build Status](https://travis-ci.com/librespot-org/librespot-java.svg?branch=master)](https://travis-ci.com/librespot-org/librespot-java)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/xyz.gianlu.librespot/librespot-java/badge.svg)](https://maven-badges.herokuapp.com/maven-central/xyz.gianlu.librespot/librespot-java)

`librespot-java` is a port of [librespot](https://github.com/librespot-org/librespot), originally written in Rust. Additionally, this implementation provides an useful API to request metadata or control the player, more [here](https://github.com/librespot-org/librespot-java/blob/master/api).

## Get started
All the configuration you need is inside the `conf.properties` file, there you can decide to authenticate with:
- Username and password
- Zeroconf
- Facebook
- Auth blob

### Username and password
This is pretty straightforward, but remember that having hardcoded passwords isn't the best thing on earth.

### Zeroconf
In this mode `librespot` becomes discoverable with Spotify Connect by devices on the same network. Just open a Spotify client and select `librespot-java` from the available devices list.

### Facebook
Authenticate with Facebook. The console will provide a link to visit in order to continue the login process.

### Auth blob
This is more advanced and should only be used if you saved an authentication blob. The blob should have already been Base64-decoded.

## Run
You can download the latest release from [here](https://github.com/librespot-org/librespot-java/releases) and then run `java -jar ./librespot-core-jar-with-dependencies.jar` from the command line.

## Build it
This project uses [Maven](https://maven.apache.org/), after installing it you can compile with `mvn clean package` in the project root, if the compilation succeeds you'll be pleased with a JAR executable in `core/target`. Remember that you need to clone the project with its submodules (`git clone --recursive https://github.com/librespot-org/librespot-java`).

To run the newly build jar run `java -jar ./core/target/librespot-core-jar-with-dependencies.jar`.