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
- Multiple named screens with independent settings
- Shared FFmpeg decoding for screens using the same URL
- Configurable dimensions, locations and orientation
- Automatic reconnect with exponential backoff
- Decoder pause when no players are near the screen
- Adaptive FPS and map-update limits
- Guided MediaMTX setup for five network situations
- Czech and English localization
- Granular permission for every management command
- Optional `luigiscreen.see.<screen>` visibility permission per screen
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
/screen create main 7 4
/screen start main
```

Complete documentation:
https://unknown-56-works.gitbook.io/luigiscreen/

## Commands

| Command | Description |
| --- | --- |
| `/screen create <name> [width] [height]` | Create a named map screen |
| `/screen clone <source> <new-name>` | Create another screen that shares the source URL |
| `/screen list` | List all screens and masked source URLs |
| `/screen start <name\|all>` | Enable one or every screen |
| `/screen stop <name\|all>` | Disable streaming without deleting screens |
| `/screen remove <name>` | Remove one screen |
| `/screen status [name]` | Show the registry summary or detailed screen state |
| `/screen set <name> <url\|fps\|distance\|enabled\|permission> <value>` | Change an independent screen setting |
| `/screen reload` | Reload configuration, localization and all screens |
| `/screen debug` | Toggle live performance statistics |
| `/screen mediamtx <situation>` | Generate a MediaMTX configuration |

`luigiscreen.admin` is granted to operators and includes every command plus
access to all protected screens. Individual permissions include
`luigiscreen.create`, `luigiscreen.remove`, `luigiscreen.start`,
`luigiscreen.stop`, `luigiscreen.status` and equivalent nodes for the other
commands.

Screens are public by default. Enable protection with:

```text
/screen set main permission true
```

Then grant `luigiscreen.see.main` or `luigiscreen.see.*` through the server's
permission plugin.

## Building

Builds require JDK 21 and Maven:

```text
mvn clean verify
```

The shaded plugin JAR is created in `target/`.

## Verification

The current suite contains 37 automated tests covering:

- RTMP URL and error-message sanitization
- Screen corner calculation for every vertical direction
- Screen dimensions and total-map validation
- Configuration limit clamping
- Adaptive FPS, minimum FPS and maximum FPS limits
- Named screen validation and legacy geometry migration helpers
- URL-based source grouping for cloned screens
- Reference-counted shared video-frame lifetime
- Granular command permission metadata
- Per-screen visibility permission naming and defaults
- MediaMTX generation
- Localization files and debug text formatting

## Platform limits

The current artifact supports Windows x86_64 and Linux x86_64. It does not
support ARM, macOS or Folia.

The default per-screen limit is 10x6 maps and 60 maps. Larger screens can
consume substantial CPU, memory and network bandwidth.

Each screen stores its own `url`, `fps`, `distance`, `world`, `location`,
`width`, `height`, `enabled` and `permission-required` value. Screens with the
exact same normalized URL automatically use one FFmpeg decoder and
independently render the shared latest frame.

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
