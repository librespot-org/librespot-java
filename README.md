# librespot-java
[![Build Status](https://travis-ci.com/librespot-org/librespot-java.svg?branch=dev)](https://travis-ci.com/librespot-org/librespot-java)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/1ec8ca04e5054558a089bc7f640079a6)](https://www.codacy.com/manual/devgianlu/librespot-java?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=librespot-org/librespot-java&amp;utm_campaign=Badge_Grade)
[![time tracker](https://wakatime.com/badge/github/librespot-org/librespot-java.svg)](https://wakatime.com/badge/github/librespot-org/librespot-java)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/xyz.gianlu.librespot/librespot-java/badge.svg)](https://maven-badges.herokuapp.com/maven-central/xyz.gianlu.librespot/librespot-java)

`librespot-java` is a port of [librespot](https://github.com/librespot-org/librespot), originally written in Rust, which has evolved into the most up-to-date open-source Spotify client. Additionally, this implementation provides a useful API to request metadata or control the player, more [here](api).

## Disclaimer!
We (the librespot-org organization and me) **DO NOT** encourage piracy and **DO NOT** support any form of downloader/recorder designed with the help of this repository and in general anything that goes against the Spotify ToS. If you're brave enough to put at risk this entire project, just don't publish it. This is meant to provide support for all those devices that are not officially supported by Spotify.

## Features
This client is pretty much capable of playing anything that's available on Spotify. 
Its main features are:
- Tracks and podcasts/episodes playback
- Stations and dailymixes support
- Local content caching
- Zeroconf (Spotify Connect)
- Gapless playback
- Mixed playlists (cuepoints and transitions)

## The library
The `lib` module provides all the necessary components and tools to interact with Spotify. More [here](lib).

## The player
The `player` module provides the full player experience. You can use it from Spotify Connect, and it operates in full headless mode. More [here](player).

## Protobuf generation
The compiled Java protobuf definitions aren't versioned, therefore, if you want to open the project inside your IDE, you'll need to run `mvn compile` first to ensure that all the necessary files are created. If the build fails due to missing `protoc` you can install it manually and use the `-DprotocExecutable=/path/to/protoc` flag.
The `com.spotify` package is reserved for the generated files. 

## Logging
The application uses Log4J for logging purposes, the configuration file is placed inside `lib/src/main/resources`, `player/src/main/resources` or `api/src/main/resources` depending on what you're working with. You can also toggle the log level with `logLevel` option in the configuration.

## Snapshots
Snapshots for all variants are available [here](https://oss.sonatype.org/content/repositories/snapshots/xyz/gianlu/librespot/).

## Related Projects
- [librespot](https://github.com/librespot-org/librespot)
- [ansible-role-librespot](https://github.com/xMordax/ansible-role-librespot/tree/master) - Ansible role that will build, install and configure librespot-java.
- [spocon](https://github.com/spocon/spocon) - Install librespot-java from APT

# Special thanks
- All the developers of [librespot](https://github.com/librespot-org/librespot) which started this project in Rust
- All the contributors of this project for testing and fixing stuff
- <a href="https://www.yourkit.com/"><img src="https://www.yourkit.com/images/yklogo.png" height="20"></a> that provided a free license for their [Java Profiler](https://www.yourkit.com/java/profiler/)
