# LuigiScreen Modrinth Listing

This file is the source of truth for the LuigiScreen Modrinth page.

## Project fields

**Project type:** Plugin

**Name:** LuigiScreen

**Slug:** `luigiscreen`

**Summary:**

> A server-side Paper/Bukkit plugin for live OBS and RTMP video on configurable Minecraft map screens.

**Recommended categories:**

- Utility
- Technology
- Decoration

**Client environment:** Unsupported

**Server environment:** Required

**Supported loader:** Paper

**Supported Minecraft version:** 1.21.11

**Required Java version:** Java 21

**Current release channel:** Alpha

**License:** GNU Affero General Public License v3.0 only

**SPDX identifier:** `AGPL-3.0-only`

## Links

**Documentation:**

https://unknown-56-works.gitbook.io/luigiscreen/

**Wiki source:**

https://github.com/unknown-566/LuigiScreen-Wiki

**Source code:**

https://github.com/unknown-566/LuigiScreen

**Issues:**

https://github.com/unknown-566/LuigiScreen/issues

**Required dependency:**

https://modrinth.com/plugin/mapengine

## Full description

````markdown
# Turn a Minecraft wall into a live screen

LuigiScreen is a server-side Paper/Bukkit plugin that displays a live RTMP video stream across a configurable wall of Minecraft maps.

Use OBS Studio to capture a desktop, game, video, browser or camera. MediaMTX receives the RTMP stream, LuigiScreen decodes its latest frame with FFmpeg, and MapEngine renders it for nearby players.

No client mod is required. Players join with a normal Minecraft client.

## Features

- Live RTMP video rendered on Minecraft maps
- Multiple named screens with independent settings
- One shared FFmpeg decoder for screens using the same URL
- Configurable screen dimensions, worlds, locations and orientation
- Automatic reconnect with exponential backoff
- Viewer-distance detection
- Decoder pause while nobody is near the screen
- Adaptive FPS protection for larger screens
- Delta map updates to reduce unnecessary traffic
- Guided MediaMTX configuration for five network situations
- Editable Czech and English localization
- Granular permissions for every management command
- Optional `luigiscreen.see.<screen>` protection per display
- Live performance boss bar and debug sidebar
- Persistent screen configuration across restarts
- Masked RTMP credentials in commands and plugin logs
- Bundled FFmpeg natives for Windows x86_64 and Linux x86_64

## Requirements

- Paper 1.21.11
- Java 21
- MapEngine 1.8.12
- MediaMTX
- OBS Studio or another RTMP publisher
- Windows x86_64 or Linux x86_64 server

LuigiScreen belongs to the Bukkit plugin ecosystem but currently targets
Paper APIs. Use Paper 1.21.11, not a plain Spigot or CraftBukkit server.

[MapEngine is a required dependency.](https://modrinth.com/plugin/mapengine)

## How it works

```text
OBS or another publisher
          |
          | RTMP
          v
       MediaMTX
          |
          | RTMP
          v
 LuigiScreen + FFmpeg
          |
          v
       MapEngine
          |
          v
 Minecraft map screen
```

MediaMTX and OBS do not have to run on the Minecraft server computer. LuigiScreen includes guided setup profiles for:

- The same computer
- A local network
- A public IP with port forwarding
- A VPN or TCP tunnel
- External hosting or a VPS

## Installation

1. Stop the Paper server completely.
2. Install MapEngine 1.8.12.
3. Put the LuigiScreen JAR in `plugins/`.
4. Start the server.
5. Choose a MediaMTX setup with `/screen mediamtx <situation>`.
6. Start MediaMTX and publish the stream from OBS.
7. Create the screen while looking at the upper-left block of a vertical wall.

Example:

```text
/screen mediamtx same-pc
/screen create main 7 4
/screen start main
```

Read the [complete step-by-step documentation](https://unknown-56-works.gitbook.io/luigiscreen/) before exposing MediaMTX to the internet.

## Commands

| Command | Description |
| --- | --- |
| `/screen create <name> [width] [height]` | Create a named screen on the wall you are looking at |
| `/screen clone <source> <new-name>` | Clone a screen and share its source URL |
| `/screen list` | List screens and masked source URLs |
| `/screen start <name\|all>` | Enable one or every screen |
| `/screen stop <name\|all>` | Disable screens without deleting them |
| `/screen remove <name>` | Remove one screen |
| `/screen status [name]` | Show registry or detailed screen state |
| `/screen set <name> <url\|fps\|distance\|enabled\|permission> <value>` | Update one screen |
| `/screen reload` | Reload configuration, localization and all screens |
| `/screen debug` | Toggle live performance statistics |
| `/screen mediamtx <situation>` | Generate a guided MediaMTX configuration |

`luigiscreen.admin` is granted to operators and includes every command.
Individual nodes such as `luigiscreen.create`, `luigiscreen.start`,
`luigiscreen.stop`, `luigiscreen.remove` and `luigiscreen.status` can be
assigned separately. Screens are public by default and can optionally require
`luigiscreen.see.<screen-name>`.

## Performance safeguards

The default public configuration limits each screen to `10x6` maps and 60 maps. Adaptive FPS and map-update limits protect the server and connected clients.

Large screens can still consume significant CPU, memory and network bandwidth. Increase the limits only after testing `/screen debug` with real players.

Screens with an identical RTMP URL share one decoder and one decoded image.
MapEngine scaling, rendering and packets still run independently for every
screen because their sizes, FPS and viewers may differ.

## Current alpha limitations

- Video only; Minecraft does not receive stream audio
- No ARM or macOS native libraries
- No Folia support
- MediaMTX is configured by LuigiScreen but runs as a separate application
- OBS or another publisher must stay online for live capture

## Alpha notice

LuigiScreen is currently an alpha project. Back up the server before updating and test new builds away from production.

Bug reports should include the output of `/screen status`, relevant console logs and the diagnostic information described in the wiki. Remove passwords, tokens and public addresses before posting logs.

## Documentation

- [Complete wiki](https://unknown-56-works.gitbook.io/luigiscreen/)
- [Installation](https://unknown-56-works.gitbook.io/luigiscreen/getting-started/installation)
- [Network setup](https://unknown-56-works.gitbook.io/luigiscreen/streaming/overview)
- [Troubleshooting](https://unknown-56-works.gitbook.io/luigiscreen/troubleshooting/common-errors)
- [Czech quick start](https://unknown-56-works.gitbook.io/luigiscreen/czech/quick-start)
````

## Current version

**Version number:** `1.1.0-alpha.9`

**Version title:** `LuigiScreen 1.1.0-alpha.9`

**Release channel:** Alpha

**Loader:** Paper

**Game version:** 1.21.11

**Java:** 21

**Primary file:**

```text
LuigiScreen-1.1.0-alpha.9.jar
```

**File size:** 55,249,902 bytes

**SHA-256:**

```text
AD3CCCAC043C24747CEEA25B04E4A2DA95267566A160403760D7B1078DCF0B25
```

**Dependency:**

- MapEngine 1.8.12: Required

## Current version changelog

```markdown
## LuigiScreen 1.1.0-alpha.9

### Highlights

- Granular permissions for every `/screen` subcommand
- `luigiscreen.admin` remains the operator-level parent permission
- Optional visibility protection for individual screens
- Dynamic `luigiscreen.see.<screen-name>` permissions
- `luigiscreen.see.*` wildcard access
- `/screen set <name> permission <true|false>`
- Public screens by default through `permission-required: false`
- 37 automated tests

### Platform support

- Paper 1.21.11
- Java 21
- Windows x86_64
- Linux x86_64
- MapEngine 1.8.12 is required

### Known limitations

- No stream audio in Minecraft
- No ARM, macOS or Folia support
- MediaMTX and an RTMP publisher are separate requirements
- Every screen still adds MapEngine render and packet cost even when decoding is shared

Read the [installation guide](https://unknown-56-works.gitbook.io/luigiscreen/getting-started/installation) before installing.
```

## Gallery plan

Upload clean screenshots without chat, coordinates, IP addresses or debug overlays unless the overlay is the subject of the image.

Recommended gallery images:

1. `Live screen` - A wide in-game view of a working 7x4 map screen.
2. `OBS to Minecraft` - OBS preview beside the matching in-game screen.
3. `Debug statistics` - The boss bar and sidebar during an active stream.
4. `Large screen` - A safe larger display showing the configurable dimensions.
5. `MediaMTX setup` - The guided `/screen mediamtx` workflow without credentials.

Recommended image title and caption:

```text
Title: Live RTMP screen
Caption: An OBS video stream rendered live on a 7x4 Minecraft map wall.
```

## Icon guidance

- Use a square PNG.
- Recommended source size: 512x512 or 1024x1024.
- Keep the important shape away from the outer edge.
- Avoid tiny text.
- A strong concept is a Minecraft-style map frame containing a green play or broadcast symbol.

## License decision

LuigiScreen Free uses `AGPL-3.0-only`.

- License text: https://github.com/unknown-566/LuigiScreen/blob/main/LICENSE
- Source code: https://github.com/unknown-566/LuigiScreen
- Third-party notices: https://github.com/unknown-566/LuigiScreen/blob/main/THIRD_PARTY_NOTICES.md
- Trademark policy: https://github.com/unknown-566/LuigiScreen/blob/main/TRADEMARKS.md

The future Plus edition is not part of this repository or Modrinth alpha release.

## Final publishing checklist

- [ ] Project icon uploaded
- [ ] Summary pasted
- [ ] Full description pasted
- [ ] Project type set to Plugin
- [ ] Paper selected
- [ ] Client environment set to Unsupported
- [ ] Server environment set to Required
- [ ] Categories selected
- [x] License selected and included in the project
- [x] Public Free source repository added
- [ ] Documentation URL added
- [ ] Issue tracker URL added
- [ ] MapEngine marked as a required dependency
- [ ] `1.1.0-alpha.9` uploaded as Alpha
- [ ] Paper 1.21.11 selected for the version
- [ ] Gallery screenshots checked for credentials and IP addresses
- [ ] Server backup and alpha warning remain visible
