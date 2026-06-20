# LuigiScreen Modrinth Listing

This file is the source of truth for the LuigiScreen Modrinth page.

## Project fields

**Project type:** Plugin

**Name:** LuigiScreen

**Slug:** `luigiscreen`

**Summary:**

> A server-side Paper/Bukkit plugin for live streams, videos, images, GIFs, playlists and event scenes on Minecraft map screens.

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
# Turn a Minecraft wall into a media screen

LuigiScreen is a server-side Paper/Bukkit plugin that displays live streams,
videos, images, GIFs, playlists and event scenes across configurable walls of
Minecraft maps.

Select an RTMP or MJPEG stream, a local video, a local or remote image, or an
animated GIF. LuigiScreen loads the latest frame and MapEngine renders it for
nearby players.

No client mod is required. Players join with a normal Minecraft client.

## Features

- RTMP and MJPEG live streams
- Looping local videos and GIFs
- Local and URL images
- Per-screen playlists with weighted random media rotation
- Manual event scenes that temporarily interrupt normal playback
- Random media folder items for videos, images and GIFs
- Multiple named screens with independent settings
- One shared loader for screens using the same source type and value
- Configurable screen dimensions, worlds, locations and orientation
- Automatic reconnect with exponential backoff
- Viewer-distance detection
- Decoder pause while nobody is near the screen
- Adaptive FPS protection for larger screens
- Delta map updates to reduce unnecessary traffic
- Optional guided MediaMTX configuration for RTMP
- Editable Czech and English localization
- Granular permissions for every management command
- Optional `luigiscreen.see.<screen>` protection per display
- Live performance boss bar and debug sidebar
- Optional asynchronous Modrinth update notifications
- Persistent screen configuration across restarts
- Masked remote-source credentials in commands and plugin logs
- Bundled FFmpeg natives for Windows x86_64 and Linux x86_64

## Requirements

- Paper 1.21.11
- Java 21
- MapEngine 1.8.12
- MediaMTX and OBS Studio only when using RTMP
- Windows x86_64 or Linux x86_64 server

LuigiScreen belongs to the Bukkit plugin ecosystem but currently targets
Paper APIs. Use Paper 1.21.11, not a plain Spigot or CraftBukkit server.

[MapEngine is a required dependency.](https://modrinth.com/plugin/mapengine)

## Source types

```text
rtmp       rtmp:// or rtmps:// live stream
mjpeg      HTTP(S) MJPEG camera stream
video      looping local video file
image      local static image
url-image  remote HTTP(S) image
gif        looping local or remote GIF
```

Relative local paths use `plugins/LuigiScreen/media/`. Screens using the same
source type and value share one loader while keeping independent FPS,
distance, location, dimensions and permissions.

## Playlists and events

Screens can also run configured playlists:

```text
/screen playlist set main spawn_rotation
```

Playlist items can use RTMP, MJPEG, video, image, URL image, GIF, text frames
or a random folder of local media files. Items support weights, durations,
cooldowns and simple conditions such as viewer count, online players, viewer
permission and TPS.

Manual event scenes can interrupt the normal playlist and then return to it:

```text
/screen event play main update_reveal
```

Events are useful for announcements, update reveals, countdowns or temporary
live streams.

For RTMP, MediaMTX and OBS do not have to run on the Minecraft server computer.
LuigiScreen includes guided setup profiles for the same computer, LAN, public
IP, VPN and external hosting.

## Installation

1. Stop the Paper server completely.
2. Install MapEngine 1.8.12.
3. Put the LuigiScreen JAR in `plugins/`.
4. Start the server.
5. Create the screen while looking at the upper-left block of a vertical wall.
6. Select its source.

Example:

```text
/screen create main 7 4
/screen source main video intro.mp4
/screen start main
```

Use `/screen mediamtx <situation>` only for an RTMP setup. Read the
[complete step-by-step documentation](https://unknown-56-works.gitbook.io/luigiscreen/)
before exposing MediaMTX to the internet.

## Commands

| Command | Description |
| --- | --- |
| `/screen create <name> [width] [height]` | Create a named screen on the wall you are looking at |
| `/screen clone <source> <new-name>` | Clone a screen and share its typed source |
| `/screen list` | List screens and safe source values |
| `/screen start <name\|all>` | Enable one or every screen |
| `/screen stop <name\|all>` | Disable screens without deleting them |
| `/screen remove <name>` | Remove one screen |
| `/screen status [name]` | Show registry or detailed screen state |
| `/screen source <name> <type> <value>` | Switch RTMP, MJPEG, video, image, URL image or GIF |
| `/screen playlist list\|set\|clear ...` | Assign or clear configured media playlists |
| `/screen event list\|play\|stop ...` | Play or stop configured manual event scenes |
| `/screen set <name> <url\|fps\|distance\|enabled\|permission> <value>` | Update one screen |
| `/screen reload` | Reload configuration without destroying screens |
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

Screens with an identical normalized source type and value share one loader
and one decoded image.
MapEngine scaling, rendering and packets still run independently for every
screen because their sizes, FPS and viewers may differ.

## Current alpha limitations

- Video only; Minecraft does not receive stream audio
- No ARM or macOS native libraries
- No Folia support
- MediaMTX and OBS remain separate applications when RTMP is used
- A publisher must stay online for live RTMP capture
- URL images are loaded once after a successful request
- Events are manual commands in this alpha; automatic triggers are planned later

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

**Version number:** `1.1.0-alpha.16`

**Version title:** `LuigiScreen 1.1.0-alpha.16`

**Release channel:** Alpha

**Loader:** Paper

**Game version:** 1.21.11

**Java:** 21

**Primary file:**

```text
LuigiScreen-1.1.0-alpha.16.jar
```

**File size:** 55,274,631 bytes

**SHA-256:**

```text
5B3B68BB9B5BA0B851CF325C5D5496CD7FB199EEF414EEA32F6129A9868366F0
```

**Dependency:**

- MapEngine 1.8.12: Required

## Current version changelog

```markdown
## LuigiScreen 1.1.0-alpha.16

### Runtime metadata fix

- Restored the Maven metadata JavaCPP uses to identify the bundled FFmpeg version
- Fixed `Version of org.bytedeco:ffmpeg could not be found` during startup
- Prevented Paper's related `System.out/err.print` nag warning
- Kept duplicate classifier metadata excluded while retaining the required base metadata
- Added a GitHub Actions check that fails if FFmpeg or JavaCPP metadata is missing again
- Verified JavaCPP reports bundled FFmpeg version `7.1.1-1.5.12`
- 54 automated tests

### Platform support

- Paper 1.21.11
- Java 21
- Windows x86_64
- Linux x86_64
- MapEngine 1.8.12 is required

### Known limitations

- No stream audio in Minecraft
- No ARM, macOS or Folia support
- MediaMTX and a publisher are only required for RTMP
- Events are manual commands in this alpha; automatic triggers are planned later
- Playlist folders are refreshed during startup or `/screen reload`
- Only versions visible through the public Modrinth API can be detected
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
5. `Playlist/event demo` - A screen showing an announcement or rotating media.

Recommended image title and caption:

```text
Title: LuigiScreen media wall
Caption: A video rendered on a configurable 7x4 Minecraft map wall.
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
- [ ] `1.1.0-alpha.16` uploaded as Alpha
- [ ] Paper 1.21.11 selected for the version
- [ ] Gallery screenshots checked for credentials and IP addresses
- [ ] Server backup and alpha warning remain visible
