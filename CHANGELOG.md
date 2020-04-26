# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.3.1] - 26-04-2020
### Added
- Added explicit content filter (#200)
- Added support for GZip requests (8fc4ae99954dcc6982462a860cf643b8373cc425)
- Compress all outgoing HTTP requests (e74e43c2919228dbb0db93b18035f00d4c2e2f2b)
- Support moving tracks inside playlist (#203)
- Added `player.volumeSteps` to configuration (#214)

### Changed
- Allow any origin for API (#188)
- Default pregain value is now 3dB (#199)
- Changed image download endpoint (8f232bcaab2ea671c8f0c45ecdb03dd0eeae1bb5)
- Handle PUT state requests asynchronously (#197)
- Include milliseconds in logs (#205)
- Fixed general issues in Websocket client (3039da7bbd4377c1c7816872b22f2d38653db6bc)
- Report active user for Zeroconf correctly (#210)
- Fixed issue with not being able to reconnect with Zeroconf (#210)
- Refactored loopers and queues (#206, #212)

### Fixed 
- Resend Shairport metadata when resuming (#195)
- Fixed NPE when reconnecting (f5071197785053dea641e75bdf7f1ca5005bae79)
- Fixed device disappearing due to concurrent reconnection (a3edb34a504d668c57ef28b58541768017d052c2)
- Fixed cache not working after replaying the same track 8 times (#201)
- Fixed issue with time synchronization PING (#202)
- Close all threads properly when shutting down (#209)


## [1.3.0] - 17-03-2020
### Added
- Added `volumeChanged` event (ab76d70ccfcf79053a5ca097783611b55d90fa81)
- Added support for Shairport-like metadata (#174, #177, #182, #183, #186)
- Added endpoint to retrieve canvases (ba10370e04f97b11ed30a7f40fb0f0d91eb66d48)
- Added feature to store credentials after first log in (f07b00ae23735f09804112f72a86c5c7b0b8ce36)
- Added API endpoint to request metadata without type (#149)
- Make device ID configurable (#178)

### Changed
- **!!** Improved playback performance (befe207a21c1cea1ffe4d641bf80394f075bed51)
- **!!** Rewritten cache system to improve performance (#179, #184)
- Log big protobuf messages only if log level is `TRACE` (38975e77a3a7b3d0745a60a66aefb89c17d9865e)
- Improved closing operations (6b6333eaea274b952a4a81120c41112104329157, #176)
- Improved seeking by clearing buffers (7c7a34f6cfc2e783f96e060c5cd386a0f7833d02)

### Fixed
- Avoid deadlock when shutting down (6659bfe0417a803f8602f7801e4f11476e56d1c4)
- Fixed issue when starting (373583e159d938475e274780a260e60efa0d65b4)
- Fixed issue with transforming (7876c10ac276cf3bb395f5b94107e942c23cb208)
- Fixed deadlock when seeking with playback paused (#175)
- Fixed playback not starting when selecting new context (2577329dacb238f430cbd1ff19fbfc65330a9d23)


## [1.2.2] - 03-02-2020
### Added
- Added HTTP and SOCKS proxy support with authentication (#172)
- Added `logLevel` option (#171)
- Added support for requesting token with multiple scopes (8c637169df1ca699abfc42ee49e9c67a11c86cb8)
- Added `connectionDropped` and `connectionEstablished` events (#172, 9c9842a743d30a16a95992f69595b1bd439a1d71)

### Fixed
- Fixed deadlock when track loading fails (c014f947eac1cdc66a1b365573ca815204cd678a)

## Changed
- Changed response codes for API requests (#172, 9c9842a743d30a16a95992f69595b1bd439a1d71)


## [1.2.1] - 17-01-2020
### Added
- Added search and tokens endpoints (ba8b2fb46352b7b92e8f785efa42a5f58459397b)
- Added `playlist` metadata endpoints (#168)

### Fixed
- Fixed synchronization issue when reading from stream (93fffc45ddd22b1111a40e58639706c33f7d817f)

### Changed
- Do not include generate protobuf files, only definitions (#170)


## [1.2.0] - 12-01-2020
### Added
- Add CORS headers to API responses (#161)
- Added timeout to Mercury requests to avoid deadlocks (1e8255bfac4c360f61712eca3da76fe90a49be84)
- Added `trackTime` to API events (9a8a515bbebce17a68142c35242f0a9d38c13254)
- Added `metadataAvailable`, `playbackHaltStateChanged`, `sessionCleared`, `sessionChanged`, `inactiveSession` events (5cfae00dfdd3fade92934c4504de6ac5268be1d4, b933939e165f4a76cc9c9cd65fb4f6d9f283d3a6)
- API server is available immediately even for Zeroconf instances (#166, b933939e165f4a76cc9c9cd65fb4f6d9f283d3a6)

### Changed
- Updated client version and type (b4d6476f49c81dc7db8f6d2c22bd7ead0a2a0095)
- Improved download retry strategy (#163, fcd47e2ee0f8774a6adc8da4149339714d498e51)
- Moved API configuration (`api.port` and `api.host`) to file (1c9a221221df0549d45003f96d4ca31297d7746f)
- Modified `player/current` endpoint to include `trackTime` parameter (9a8a515bbebce17a68142c35242f0a9d38c13254)

### Fixed
- Do not shuffle mixed playlists if not allowed (2e35d9bf74f12a417302a9b1ea43ab72c7cd7b1d)
- Fixed NTP UDP socket timeout (4e5c5749039f98346f2f7fa0c4cded84b98d064d)
- Fixed official client not working after disconnecting (#165)
- Update time (`pcm_offset` in Vorbis codec) when seeking (553fa315663279754e0f5d19d1012073dc6d8bd3)
- Fixed pausing state being overridden in some cases (651d6d78e10773103867131660a746669def74ea)


## [1.1.0] - 13-12-2019
### Added
- Websocket API to listen to player events
- Ability to change the API port via `api.port`
- Retry request when Spotify sends 503

### Changed
- Fixed deadlocks
- Fixed synchronization issue
