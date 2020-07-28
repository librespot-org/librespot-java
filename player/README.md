# Player
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/xyz.gianlu.librespot/librespot-player/badge.svg)](https://maven-badges.herokuapp.com/maven-central/xyz.gianlu.librespot/librespot-player)

This module allows running `librespot-java` in headless mode as a Spotify Connect device.

## Get started
All the configuration you need is inside the `config.toml` file. If none is present, a sample `config.toml` will be generated the first time the jar is run. There you can decide to authenticate with:
- Username and password
- Zeroconf
- Facebook
- Auth blob
- Stored credentials

The suggested way to authenticate if you're not considering Zeroconf is to:
1) Enable stored credentials:
    ```toml
    [auth]
       storeCredentials = true
       credentialsFile = "some_file.json"
    ```
2) Authenticate with username and password:
    ```toml
    [auth]
       strategy = "USER_PASS"
       username = "<username>"
       password = "<password>"
    ```
3) Set authentication strategy to stored credentials and remove sensible data:
    ```toml
    [auth]
       strategy = "STORED"
       username = ""
       password = ""
    ```

### Username and password
> ```toml
> [auth]
>     strategy = "USER_PASS"
>     username = "<username>"
>     password = "<password>"
> ```
This is the simplest authentication method, but less secure because you'll have a plaintext password in your configuration file.

### Zeroconf
> ```toml
> [auth]
>     strategy = "ZEROCONF"
> ```
Becomes discoverable with Spotify Connect by devices on the same network, connect from the devices list.
If you have a firewall, you need to open the UDP port `5355` for mDNS. Then specify some random port in `zeroconf.listenPort` and open that TCP port too.

### Facebook
> ```toml
> [auth]
>     strategy = "FACEBOOK"
> ```
Authenticate with Facebook. The console will provide a link to visit in order to continue the login process.

### Auth blob
> ```toml
> [auth]
>     strategy = "BLOB"
>     blob = "dGhpcyBpcyBzb21lIGJhc2U2NCBkYXRhIQ=="
> ```
This is more advanced and should only be used if you saved an authentication blob. The blob should be in Base64 format. Generating one is currently not a feature of librespot-java.

### Stored credentials
> ```toml
> [auth]
>     strategy = "STORED"
>     credentialsFile = "some_file.json"
> ```
Stored credentials are generated and saved into `auth.credentialsFile` if `auth.storeCredentials` is set to `true` and `auth.strategy` is not `ZEROCONF`. The file created is a JSON file that allows you to authenticate without having plaintext passwords in your configuration file (and without triggering a login email).
 

## Run
You can download the latest release from [here](https://github.com/librespot-org/librespot-java/releases) and then run `java -jar librespot-player-jar-with-dependencies.jar` from the command line.

### Audio output configuration
On some systems, many mixers could be installed making librespot-java playback on the wrong one, therefore you won't hear anything and likely see an exception in the logs. If that's the case, follow the guide below:
1) In your configuration file (`config.toml` by default), under the `player` section, make sure `logAvailableMixers` is set to `true` and restart the application
2) Connect to the client and start playing something
3) Along with the previous exception there'll be a log message saying "Available mixers: ..."
4) Pick the right mixer and copy its name inside the `mixerSearchKeywords` option. If you need to specify more search keywords, you can separate them with a semicolon
5) Restart and enjoy

> **Linux note:** librespot-java will not be able to detect the mixers available on the system if you are running headless OpenJDK. You'll need to install a headful version of OpenJDK (usually doesn't end with `-headless`).

## Build it
This project uses [Maven](https://maven.apache.org/), after installing it you can compile with `mvn clean package` in the project root, if the compilation succeeds you'll be pleased with a JAR executable in `player/target`.
To run the newly build jar run `java -jar player/target/librespot-player-jar-with-dependencies.jar`.