# Library
This module contains all the necessary components to interact with the Spotify infrastructure, but doesn't require configuration files or additional system resources.

## Getting started
The core of all components is the [Session class](src/main/java/xyz/gianlu/librespot/core/Session.java), it takes care of connecting, authenticating and setting everything up.

```java
Session.Configuration conf = new Session.Configuration.Builder()
    .setCacheEnabled()
    .setCacheDir()
    .setDoCacheCleanUp()
    .setStoreCredentials()
    .setStoredCredentialsFile()
    .setTimeSynchronizationMethod()
    .setTimeManualCorrection()
    .setProxyEnabled()
    .setProxyType()
    .setProxyAddress()
    .setProxyPort()
    .setProxyAuth()
    .setProxyUsername()
    .setProxyPassword()
    .setRetryOnChunkError()
    .build();


Session.Builder builder = new Session.Builder(conf)
    .setPreferredLocale()
    .setDeviceType()
    .setDeviceName()
    .setDeviceId();

builder.userPass("<username>", "<password>"); // See other authentication methods

Session session = builder.create();

session.mercury(); // Mercury client
session.audioKey(); // Request audio keys for AES decryption
session.cdn(); // Request content from CDN
session.tokens(); // Request access tokens
session.api(); // Request metadata and other data
session.contentFeeder(); // Request tracks, images, etc
session.search(); // Perform search
```

You can also instantiate the player:
```java
PlayerConfiguration conf = new PlayerConfiguration.Builder()
        .setAutoplayEnabled()
        .setCrossfadeDuration()
        .setEnableNormalisation()
        .setInitialVolume()
        .setLogAvailableMixers()
        .setMetadataPipe()
        .setMixerSearchKeywords()
        .setNormalisationPregain()
        .setOutput()
        .setOutputPipe()
        .setPreferredQuality()
        .setPreloadEnabled()
        .setReleaseLineDelay()
        .setVolumeSteps()
        .build();

Player player = new Player(conf, session);
```

A proper implementation is available in the [Main class](../player/src/main/java/xyz/gianlu/librespot/player/Main.java) of the player.