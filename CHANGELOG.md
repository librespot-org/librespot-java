# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.6.3] - 17-05-2023
### Added
- Added client token support (08b7890ed0fadc072052958945ac64e784232ac5)
- Added onPlaybackFailed callback (#449, #460)

### Fixed
- Make AP resolver use configured proxy (#441)
- Refresh AP pool after stopping the receiver (#464)
- Bumped client version to fix PremiumAccountRequired (#614, #615)

### Changed
- Migrate some APIs to their HTTP equivalent (6b45130cf26d47ab538abe265cf351096faccc2c)
- Updated two years of dependencies

## [1.6.2] - 11-12-2021
### Added
- Added HTTPS proxy support (#390)
- Added `GET /instance` API endpoint (#403)

### Fixed
- Fixed track repeat behavior (#383)
- Fixed cache synchronization issue (b3d61f4c3bb7398e2f2246f6aa274f8d903c492b)
- Minimize audio pop when using pipe (#389)
- Fix CVE-2021-44228 (#438)

## [1.6.1] - 18-07-2021
### Added
- Added `/discovery/list` API endpoint (#352)
- Added environment variables to shell event hooks (#368)
- Added `shell.executeWithBash` configuration option (#368)

### Changed
- Space volume events 500ms apart (#369)

### Fixed
- Removed redundant pfls message (#361)
- Refresh AP pool before reconnecting (591fb92db5931d611779ab83f9747f4decaea3f7)

## [1.6.0] - 09-05-2021
### Added
- Added extended metadata API (#311)
- Added `player.bypassSinkVolume` to ignore volume events (#317)
- Added support for shell events (#329)
- Added API endpoints for repeat and shuffle toggle (7747677641993c012e72135ee3d38ea48591f326, #330)
- Configurable connection timeout (#328)
- Added artifact with `thin` classifier to `player` module (29e6dac909bf3a0a8ddeacd9e91a0f926b5cc664)
- Added Login5 API code (#322)
- Added `/discovery/list` API endpoint to list available Spotify Connect devices (#352)

### Changed
- Improved metadata DACP pipe (#317, 6a2679d8f2e26b31bbb85419fa524913d64ffe86, c10a1c82c90ba29454206587d793b1d706735a7d)
- Use slf4j for logging in `lib` and `player` modules (#336, #338)
- Moved sink code to `sink-api` and `sink` modules (#337) 
- Moved DACP code to `dacp` module (7221b2b22b03652c4505ae2f750f40610b783bbd)
- Refactored `PlayableId` and stream loading to prepare for local files playback (#208)
- Moved decoder code to `decoder-api` module (#343, 34ec54647397c0495f5dddeb193c75b347cbb351, 77a558475d860bd5c672bc58dac7baeb17e219c6)
- Centralize Base64 decoding/encoding (#351)

### Fixed
- Fixed unsupported tracks playback (#332)

## [1.5.5] - 13-02-2021
### Added
- Added `playbackEnded` event to the API (#297)
- Added `POST /instance/terminate` and `POST /instance/close` to the API (f83101059c94a636a94725619a1ea53eab2e574f, b288bd1785579af05ee4042e76cef0f801298998, 9fbe6431b961154c1efa14ad61a5dee48022377e)

### Changed
- `Player#waitReady()` now waits for the player to become ready, the old behaviour has been renamed to `Player#ready()` (dce26768eebf7e9e039ffabfceb80b66c1a9228b)


## [1.5.4] - 11-01-2021
### Fixed
- Fixed playback not starting (#281, #289, 0e85e1a52a1082dc1c63d79c432107bdeb9ed5b6)
- Fixed instance stuck after failing to load autoplay (#284)
- Do not report a null session to session listeners (#291)

### Changed
- Removed redundant calls to getInstance (#292)


## [1.5.3] - 28-12-2020
### Added
- Dispatch session closing when using Zeroconf (5b53af7aa5c23a5ed5153e68c5b022aee63ca3e6)
- Allow set volume with relative steps count (#273)

### Changed
- Improved thread usage (#249, #280)
- Catch and log exceptions happening in command/message handling (352d6e3c0cc0a36ea676505e0f761f678f4b11b8,
  3229d2cc72ad2c4d364f7c197b0aec38083d577a)

### Fixed
- Fixed parsing some payloads (f6191ff6636dd538b5ebf1090df4eaa9bd062110)
- Fixed double reading of first chunk (e5a8ae712114c344aca43e5ec5f557f7ce9657b9)
- Fixed proxy auth (#274)
- Fixed playback not starting (#277, 26818c619e2fa6f0d4da9f4e96a8e913ccc0468d, abde6b64d68d49e637343560514efc2dca895815,
  a79e78a716df74e582504b5d69df455404cef356)


## [1.5.2] - 17-11-2020
### Added
- Added `Player#waitReady()` method (d3149d3843e066986524e14369c5871c22629810)
- Added pass through endpoints for official Spotify API (#255)
- Store and check hash of first chunk of cache data (9ab9f43a91ebbce0e9a3a3c6f3c55a714c756525)

### Fixed
- Fixed `UnsupportedOperationException` when starting playback (#251)
- Close cache files correctly (e953129ed5f0dc4e9931660bd216267557d6010a, #253)
- Fixed starting playback from API (#254)


## [1.5.2] - 17-11-2020
### Added
- Added `Player#waitReady()` method (d3149d3843e066986524e14369c5871c22629810)
- Added pass through endpoints for official Spotify API (#255)
- Store and check hash of first chunk of cache data (9ab9f43a91ebbce0e9a3a3c6f3c55a714c756525)

### Fixed
- Fixed `UnsupportedOperationException` when starting playback (#251)
- Close cache files correctly (e953129ed5f0dc4e9931660bd216267557d6010a, #253)
- Fixed starting playback from API (#254)


## [1.5.1] - 31-07-2020
### Fixed
- Fixed issue with Zeroconf (#246)


## [1.5.0] - 28-07-2020
### Breaking changes
- Separated library from player (#245)
- Removed `common` module, moved into `lib`
- Removed `core` module, split into `lib` and `player`
- Moved many classes

### Added
- Added `STORED` authentication strategy (17ba408b844554632e180d3ad1e8fc7cb9db2b6c)
- Added followers and following endpoint to API (#241)
- Added toggle play/pause command to API (#244)

### Changed
- Release versions are compiled on Java 8 (5a97a60c62f002444b3a695ee7dbd6146fc52b2d)
- Refactored line acquisition to prefer native lines over conversion (#240)
- Refactored audio decrypt (b71af8376fa1d12212c679b6fdd83c20cdfd9361)

### Fixed
- Do not panic when trying to autoplay search context (2e41807ceb40af1034f2da088af545e44169b1d2)
- Fixed payload too large when sending state (#239) 


## [1.4.0] - 14-06-2020
### Added
- Report to server that we played a track (#155, still buggy)
- Retrieve tracks in state from API (#222)
- Add/remove tracks from queue from API (#222)
- Seek from API (6104db1a66b9defabc43ec21e6668899cf8c2683)
- Added logout feature (5839d5b48133cd69cf59a028ee423b44caec8627)

### Changed
- Rewritten player and related bug fixing (#155, 08282b94d6adc7e5e5a07fe20c150829fc912943, #216, #217, 13423cdcfd39b8a5722e3a14bb19e53ab426b4ea, e549f08356696ef46add79f60ad8e28891738bd7)
- Using Log4j2 (5bb16797b78a76618821d28f547e92c217bdb8c8, 73a668c47db36932567777216330479d8147d80b, 52a60cbf0c059d4582dfe278a65bf786a4df43a3, 548163e4d3b2267d3030f7597ed5b4e8eeeab29c)
- Improved error message for mixers (#220)
- Refactored audio quality selection (#223)
- Close readers properly (6ad0f3cddc89e6d172eb16c5635ed46128eb65db)
- Better truncation of sensitive values (661c171fcd353471924a4ae0544dc27ff0ca83d0)

### Fixed
- Fixed time bar in wrong position after resuming (#213)
- Do not get Cipher instance every time (#215)
- Fixed loading of some podcasts (#223)
- Fixed crash when pressing next after adding song to queue (#226)
- Prevent deadlock when closing after network issue (#227)
- Fixed old issue with Zeroconf active session (#225, #229, #231)
- Avoid establishing two connections (afc7dc366379391f7e59ce8a37a0b007b2c69303)
- Start line before writing for the first time (#232)
- Do not close `System.out` for any reason (#234)
- Shutdown OkHttp threads when closing (#235)


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
