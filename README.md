# librespot-java
`librespot-java` is a port of [librespot](https://github.com/librespot-org/librespot), originally written in Rust.

## Get started
This implementation doesn't have an user interface (at the moment) therefore you can interact with it only from an original Spotify client. To understand how you can use this have a look at the [Main.java](https://github.com/librespot-org/librespot-java/blob/master/src/main/java/org/librespot/spotify/Main.java), there you can decide how to authenticate:
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

## TODO
The client is pretty functional as it is, but improvements can be made:
- Have an user interface
- ~Caching~ (#18)
- Preloading
- Gapless playing
