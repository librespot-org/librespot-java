# librespot-java
[![Build Status](https://travis-ci.com/librespot-org/librespot-java.svg?branch=dev)](https://travis-ci.com/librespot-org/librespot-java)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/1ec8ca04e5054558a089bc7f640079a6)](https://www.codacy.com/manual/devgianlu/librespot-java?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=librespot-org/librespot-java&amp;utm_campaign=Badge_Grade)
[![time tracker](https://wakatime.com/badge/github/librespot-org/librespot-java.svg)](https://wakatime.com/badge/github/librespot-org/librespot-java)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/xyz.gianlu.librespot/librespot-java/badge.svg)](https://maven-badges.herokuapp.com/maven-central/xyz.gianlu.librespot/librespot-java)

`librespot-java` is a port of [librespot](https://github.com/librespot-org/librespot), originally written in Rust, which has evolved into the most up-to-date open-source Spotify client. Additionally, this implementation provides a useful API to request metadata or control the player, more [here](api).

## Disclaimer!
We (the librespot-org organization and me) **DO NOT** encourage piracy and **DO NOT** support any form of downloader/recorder designed with the help of this repository. If you're brave enough to put at risk this entire project, just don't publish it. This is meant to provide support for all those devices that are not officially supported by Spotify.

## Features
This client is pretty much capable of playing anything that's available on Spotify. 
Its main features are:
- Tracks and podcasts/episodes playback
- Stations and dailymixes support
- Local content caching
- Zeroconf (Spotify Connect)
- Gapless playback
- Mixed playlists (cuepoints and transitions)

## Get started
All the configuration you need is inside the `config.toml` file, there you can decide to authenticate with:
- Username and password
- Zeroconf
- Facebook
- Auth blob

### Username and password
This is pretty straightforward, but remember that having hardcoded passwords isn't the best thing on earth.

### Zeroconf
In this mode `librespot` becomes discoverable with Spotify Connect by devices on the same network. Just open a Spotify client and select `librespot-java` from the available devices list. 

If you have a firewall, you need to open the UDP port `5355` for mDNS. Then specify some random port in `zeroconf.listenPort` and open that TCP port too.

### Facebook
Authenticate with Facebook. The console will provide a link to visit in order to continue the login process.

### Auth blob
This is more advanced and should only be used if you saved an authentication blob. The blob should have already been Base64-decoded.

## Run
You can download the latest release from [here](https://github.com/librespot-org/librespot-java/releases) and then run `java -jar ./librespot-core-jar-with-dependencies.jar` from the command line.

### Audio output configuration
On some systems, many mixers could be installed making librespot-java playback on the wrong one, therefore you won't hear anything and likely see an exception in the logs. If that's the case, follow the guide below:

1) In your configuration file (`config.toml` by default), under the `player` section, make sure `logAvailableMixers` is set to `true` and restart the application
2) Connect to the client and start playing something
3) Along with the previous exception there'll be a log message saying "Available mixers: ..."
4) Pick the right mixer and copy its name inside the `mixerSearchKeywords` option. If you need to specify more search keywords, you can separate them with a semicolon
5) Restart and enjoy

> **Linux note:** librespot-java will not be able to detect the mixers available on the system if you are running headless OpenJDK. You'll need to install a headful version of OpenJDK (usually doesn't end with `-headless`).

## Build it
This project uses [Maven](https://maven.apache.org/), after installing it you can compile with `mvn clean package` in the project root, if the compilation succeeds you'll be pleased with a JAR executable in `core/target`.
To run the newly build jar run `java -jar ./core/target/librespot-core-jar-with-dependencies.jar`.

## Protobuf generation
The compiled Java protobuf definitions aren't versioned, therefore, if you want to open the project inside your IDE, you'll need to run `mvn compile` first to ensure that all the necessary files are created. If the build fails due to missing `protoc` you can install it manually and use the `-DprotocExecutable=/path/to/protoc` flag.

The `com.spotify` package is reserved for the generated files. 

## Logging
The application uses Log4J for logging purposes, the configuration file is placed inside `core/src/main/resources` or `api/src/main/resources` depending on what you're working with. You can also toggle the log level with `logLevel` option in the configuration.

## Related Projects
- [librespot](https://github.com/librespot-org/librespot)
- [ansible-role-librespot](https://github.com/xMordax/ansible-role-librespot/tree/master) - Ansible role that will build, install and configure librespot-java.
- [spocon](https://github.com/spocon/spocon) - Install librespot-java from APT

# Special thanks
- All the developers of [librespot](https://github.com/librespot-org/librespot) which started this project in Rust
- All the contributors of this project for testing and fixing stuff
- <a href="https://www.yourkit.com/"><img src="https://www.yourkit.com/images/yklogo.png" height="20"></a> that provided a free license for their [Java Profiler](https://www.yourkit.com/java/profiler/)
