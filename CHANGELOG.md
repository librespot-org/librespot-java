# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
