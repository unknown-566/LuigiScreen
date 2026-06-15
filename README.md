# LuigiScreen

LuigiScreen Free is a server-side Paper/Bukkit plugin that displays live
streams, videos, images and GIFs on configurable walls of Minecraft maps.

Author: **unknown_56**

LuigiScreen loads the selected media source, decodes its latest frame and
renders it through MapEngine. Players do not need a client mod.

> LuigiScreen is currently alpha software. Back up the server and test new
> builds away from production.

## Features

- RTMP and MJPEG live streams
- Looping local videos and GIFs
- Local and URL images
- Multiple named screens with independent settings
- Shared decoding/loading for screens using the same source type and value
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
- Masked remote-source credentials in plugin output
- Windows x86_64 and Linux x86_64 FFmpeg natives

## Requirements

- Paper 1.21.11
- Java 21
- MapEngine 1.8.12
- MediaMTX and OBS Studio only when using RTMP
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
5. Create a screen while looking at the upper-left block of a vertical wall.
6. Select a source with `/screen source`.

Example:

```text
/screen create main 7 4
/screen source main image logo.png
/screen start main
```

Complete documentation:
https://unknown-56-works.gitbook.io/luigiscreen/

## Commands

| Command | Description |
| --- | --- |
| `/screen create <name> [width] [height]` | Create a named map screen |
| `/screen clone <source> <new-name>` | Create another screen that shares its typed source |
| `/screen list` | List all screens and masked source values |
| `/screen start <name\|all>` | Enable one or every screen |
| `/screen stop <name\|all>` | Disable streaming without deleting screens |
| `/screen remove <name>` | Remove one screen |
| `/screen status [name]` | Show the registry summary or detailed screen state |
| `/screen source <name> <type> <value>` | Switch RTMP, MJPEG, video, image, URL image or GIF |
| `/screen set <name> <url\|fps\|distance\|enabled\|permission> <value>` | Change an independent screen setting |
| `/screen reload` | Reload configuration and localization without destroying screens |
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

The current suite contains 44 automated tests covering:

- RTMP URL and error-message sanitization
- Screen corner calculation for every vertical direction
- Screen dimensions and total-map validation
- Configuration limit clamping
- Adaptive FPS, minimum FPS and maximum FPS limits
- Named screen validation and legacy geometry migration helpers
- Typed source validation, aliases and shared-source grouping
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

Each screen stores its own `source.type`, `source.value`, `fps`, `distance`, `world`, `location`,
`width`, `height`, `enabled` and `permission-required` value. Screens with the
exact same normalized source type and value automatically use one loader and
independently render the shared latest frame.

## Media sources

Relative local paths are resolved inside `plugins/LuigiScreen/media/`.

```text
/screen source main rtmp rtmp://127.0.0.1:55556/screen
/screen source main mjpeg http://camera.local/video
/screen source main video intro.mp4
/screen source main image poster.png
/screen source main url-image https://example.com/poster.png
/screen source main gif animation.gif
```

Local videos and GIFs loop automatically. Existing `url:` config entries and
`/screen set <name> url ...` remain supported as legacy RTMP syntax.

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
