# API
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/xyz.gianlu.librespot/librespot-api/badge.svg)](https://maven-badges.herokuapp.com/maven-central/xyz.gianlu.librespot/librespot-api)

This module depends on `librespot-player` and, in addition, provides an API to interact with the Spotify client.

## Available endpoints
All the endpoints will respond with `200` if successful or:
- `204` If there isn't any active session (Zeroconf only)
- `500` If the session is invalid
- `503` If the session is reconnecting (`Retry-After` is always 10 seconds)

### Player
- `POST /player/load` Load a track from a given URI. The request body should contain two parameters: `uri` and `play`.
- `POST /player/play-pause` Toggle play/pause status. Useful when using a remote.
- `POST /player/pause` Pause playback.
- `POST /player/resume` Resume playback.
- `POST /player/next` Skip to next track.
- `POST /player/prev` Skip to previous track.
- `POST /player/seek` Seek to a given position in ms specified by `pos`.
- `POST /player/set-volume` Set volume to a given `volume` value from 0 to 65536.
- `POST /player/volume-up` Up the volume a little bit.
- `POST /player/volume-down` Lower the volume a little bit.
- `POST /player/current` Retrieve information about the current track (metadata and time).
- `POST /player/tracks` Retrieve all the tracks in the player state with metadata, you can specify `withQueue`.
- `POST /player/addToQueue` Add a track to the queue, specified by `uri`.
- `POST /player/removeFromQueue` Remove a track from the queue, specified by `uri`.

### Metadata
- `POST /metadata/{type}/{uri}` Retrieve metadata. `type` can be one of `episode`, `track`, `album`, `show`, `artist` or `playlist`, `uri` is the standard Spotify uri.
- `POST /metadata/{uri}` Retrieve metadata. `uri` is the standard Spotify uri, the type will be guessed based on the provided uri.

### Search
- `POST /search/{query}` Make a search.

### Tokens
- `POST /token/{scope}` Request an access token for a specific scope (or a comma separated list of scopes).

### Profile
- `GET /profile/{user_id}/followers` Retrieve a list of profiles that are followers of the specified user
- `GET /profile/{user_id}/following` Retrieve a list of profiles that the specified user is following

### Events
You can subscribe for players events by creating a WebSocket connection to `/events`.
The currently available events are:
- `contextChanged` The Spotify context URI changed
- `trackChanged` The Spotify track URI changed
- `playbackPaused` Playback has been paused
- `playbackResumed` Playback has been resumed
- `volumeChanged` Playback volume changed
- `trackSeeked` Track has been seeked
- `metadataAvailable` Metadata for the current track is available
- `playbackHaltStateChanged` Playback halted or resumed from halt
- `sessionCleared` Current session went away (Zeroconf only)
- `sessionChanged` Current session changed (Zeroconf only)
- `inactiveSession` Current session is now inactive (no audio)
- `connectionDropped` A network error occurred and we're trying to reconnect
- `connectionEstablished` Successfully reconnected
- `panic` Entered the panic state, playback is stopped. This is usually recoverable.

### Web API pass through
Use any endpoint from the [public Web API](https://developer.spotify.com/documentation/web-api/reference/) by appending it to `/web-api/`, the request will be made to the API with the correct `Authorization` header and the result will be returned.
The method, body, and content type headers will pass through. Additionally, you can specify an `X-Spotify-Scope` header to override the requested scope, by default all will be requested.

## Examples
`curl -X POST -d "uri=spotify:track:xxxxxxxxxxxxxxxxxxxxxx&play=true" http://localhost:24879/player/load`

`curl -X POST http://localhost:24879/metadata/track/spotify:track:xxxxxxxxxxxxxxxxxxxxxx`

`curl -X POST http://localhost:24879/metadata/spotify:track:xxxxxxxxxxxxxxxxxxxxxx`

`curl -X GET http://localhost:24879/web-api/v1/me/top/artists`
