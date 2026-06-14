# LuigiScreen

LuigiScreen Free is a server-side Paper/Bukkit plugin that displays a live
RTMP video stream on a configurable wall of Minecraft maps.

OBS Studio or another publisher sends video to MediaMTX. LuigiScreen decodes
the latest frame with FFmpeg and renders it through MapEngine. Players do not
need a client mod.

> LuigiScreen is currently alpha software. Back up the server and test new
> builds away from production.

## Features

- Live RTMP video on Minecraft map screens
- Configurable dimensions and orientation
- Automatic reconnect with exponential backoff
- Decoder pause when no players are near the screen
- Adaptive FPS and map-update limits
- Guided MediaMTX setup for five network situations
- Czech and English localization
- Performance boss bar and debug sidebar
- Persistent screen configuration
- Masked RTMP credentials in plugin output
- Windows x86_64 and Linux x86_64 FFmpeg natives

## Requirements

- Paper 1.21.11
- Java 21
- MapEngine 1.8.12
- MediaMTX
- OBS Studio or another RTMP publisher
- Windows x86_64 or Linux x86_64 server

LuigiScreen uses the Bukkit plugin ecosystem but currently targets Paper APIs.
Install it on Paper, not on a plain Spigot or CraftBukkit server.

MapEngine is a required runtime dependency:
https://modrinth.com/plugin/mapengine

## Installation

1. Stop the Paper server completely.
2. Install MapEngine 1.8.12.
3. Put the LuigiScreen JAR in `plugins/`.
4. Start the server.
5. Generate a MediaMTX setup with `/screen mediamtx <situation>`.
6. Start MediaMTX and publish a stream from OBS.
7. Create a screen while looking at the upper-left block of a vertical wall.

Example:

```text
/screen mediamtx same-pc
/screen create 7 4
/screen start
```

Complete documentation:
https://unknown-56-works.gitbook.io/luigiscreen/

## Commands

| Command | Description |
| --- | --- |
| `/screen create <width> <height>` | Create a map screen |
| `/screen start` | Start the decoder and renderer |
| `/screen stop` | Stop streaming without deleting the screen |
| `/screen remove` | Remove the configured screen |
| `/screen status` | Show screen and stream state |
| `/screen reload` | Reload configuration, localization and the screen |
| `/screen debug` | Toggle live performance statistics |
| `/screen mediamtx <situation>` | Generate a MediaMTX configuration |

The `luigiscreen.admin` permission is granted to operators by default.

## Building

Builds require JDK 21 and Maven:

```text
mvn clean verify
```

The shaded plugin JAR is created in `target/`.

## Platform limits

The current artifact supports Windows x86_64 and Linux x86_64. It does not
support ARM, macOS or Folia.

The default screen limit is 10x6 maps and 60 maps total. Larger screens can
consume substantial CPU, memory and network bandwidth.

## Free and Plus editions

This repository contains LuigiScreen Free only.

A future LuigiScreen Plus edition may be distributed separately under
different commercial terms. No Plus source code or license is included here.

## License

LuigiScreen Free is copyright (C) 2026 unknown-566 and licensed under the GNU
Affero General Public License v3.0 only. See [LICENSE](LICENSE).

The code license does not grant rights to present modified builds as official
LuigiScreen releases. See [TRADEMARKS.md](TRADEMARKS.md).

Third-party components and their licenses are documented in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Contributing and security

- Bug reports: https://github.com/unknown-566/LuigiScreen/issues
- Contribution policy: [CONTRIBUTING.md](CONTRIBUTING.md)
- Sensitive reports: [SECURITY.md](SECURITY.md)
