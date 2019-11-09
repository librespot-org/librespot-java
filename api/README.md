# librespot-api
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/xyz.gianlu.librespot/librespot-api/badge.svg)](https://maven-badges.herokuapp.com/maven-central/xyz.gianlu.librespot/librespot-api)

This module depends on `librespot-core` and provides an API to interact with the Spotify client.

## How it works
This API uses JSON over Websocket according to the [JSON-RPC 2.0 standard](https://www.jsonrpc.org/specification). Three method prefixes are available:
- `player`, just a placeholder
- `metadata`, allows to retrieve some useful data about tracks and playlist (more to come)
- `mercury`, allows to send requests with Mercury directly, therefore all URIs must start with `hm://`

## Client
You can find a suitable client [here](https://github.com/librespot-org/librespot-java/tree/master/api-client). 
