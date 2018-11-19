# librespot-java
`librespot-java` is a port of [librespot](https://github.com/librespot-org/librespot), originally written in Rust.

## Get started
This implementation doesn't have an user interface (at the moment) therefore you can interact with it only from an original Spotify client. To understand how you can use this have a look at the [Main.java](https://github.com/librespot-org/librespot-java/blob/master/core/src/main/java/org/librespot/spotify/Main.java), there you can decide how to authenticate:
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

## Run
After you've setup the [Main class](https://github.com/librespot-org/librespot-java/blob/master/core/src/main/java/org/librespot/spotify/Main.java), you can proceed and compile the project. Install [Maven](https://maven.apache.org/) and run `mvn clean package` in the project root, if the compilation succeeds you'll be pleased with a JAR executable in `core/target`. Remember that you need to clone the project with its submodules (`git clone --recursive https://github.com/librespot-org/librespot-java`).

## TODO
The client is pretty functional as it is, but improvements can be made:
- Have an user interface
- ~Caching~ (#18)
- Preloading
- Gapless playing
