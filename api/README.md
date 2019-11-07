# librespot-api
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/xyz.gianlu.librespot/librespot-api/badge.svg)](https://maven-badges.herokuapp.com/maven-central/xyz.gianlu.librespot/librespot-api)

This module depends on `librespot-core` and provides an API to interact with the Spotify client.

## Available endpoints

- `POST \player\load` Load a track from a given uri. The request body should contain two parameters: `uri` and `play`.
- `POST \player\pause` Pause playback.
- `POST \player\resume` Resume playback.
- `POST \player\next` Skip to next track.
- `POST \player\prev` Skip to previous track.
- `POST \player\set-volume` Set volume to a given `volume` value from 0 to 65536.
- `POST \player\volume-up` Up the volume a little bit.
- `POST \player\volume-down` Lower the volume a little bit.

## Example

`curl -X POST -d "uri=spotify:track:xxxxxxxxxxxxxxxxxxxxxx&play=true" http://localhost:24879/player/load`

