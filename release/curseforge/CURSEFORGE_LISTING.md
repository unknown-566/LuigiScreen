# LuigiScreen CurseForge Listing

This file is the source of truth for the LuigiScreen CurseForge project page.

## Project creation fields

**Game:** Minecraft

**Project type:** Bukkit Plugins

**Name:** LuigiScreen

Do not include Minecraft, Paper, the game version or the plugin version in the
project name.

**Slug:** `luigiscreen`

**Summary:**

> A server-side Paper/Bukkit plugin for live OBS and RTMP video on configurable Minecraft map screens.

**Primary category:** Admin Tools

**Additional categories, if available:**

- Fun
- Miscellaneous

Do not select categories that imply client-side installation, video audio
playback or native support that LuigiScreen does not provide.

**License:** GNU Affero General Public License v3.0

**License identifier:** `AGPL-3.0-only`

**Source code:**

https://github.com/unknown-566/LuigiScreen

**Issue tracker:**

https://github.com/unknown-566/LuigiScreen/issues

**Wiki:**

https://unknown-56-works.gitbook.io/luigiscreen/

**Donation link:** Leave empty for the alpha release.

**Client installation:** Not required

**Server installation:** Required

## Required project avatar

CurseForge requires a `400x400` project avatar.

Use:

- PNG format
- Exactly 400x400 pixels
- Original LuigiScreen artwork
- No Minecraft or third-party copyrighted logo
- No tiny text
- No AI-generated showcase that could misrepresent the plugin

Recommended concept:

```text
A dark map-frame outline containing a bright green screen and a simple white
broadcast signal, with no text.
```

## Full project description

Paste the content inside this block into the CurseForge description editor.

````markdown
# Turn a Minecraft wall into a live video screen

LuigiScreen is a server-side Paper/Bukkit plugin that renders a live RTMP
video stream across a configurable wall of Minecraft maps.

OBS Studio or another RTMP publisher captures a desktop, game, browser, camera
or video. MediaMTX receives the stream, LuigiScreen decodes the latest video
frame with FFmpeg, and MapEngine displays it to nearby players.

Players do not need to install a client mod.

## Features

- Live RTMP video rendered on Minecraft map screens
- Multiple named screens with independent settings
- One shared FFmpeg decoder for screens using the same URL
- Configurable screen width, height, world, location and wall orientation
- Persistent screen configuration across server restarts
- Automatic RTMP reconnect with exponential backoff
- Viewer-distance detection
- Decoder pause while nobody is near the screen
- Adaptive FPS protection for larger displays
- Delta map updates to reduce unnecessary packet traffic
- Configurable map-update rate limits
- Guided MediaMTX setup for five common network situations
- Editable Czech and English localization
- Granular permissions for every management command
- Optional `luigiscreen.see.<screen>` protection per display
- Live performance boss bar
- Rotating 15-line debug sidebar
- Masked RTMP credentials in status output and plugin logs
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

**MapEngine 1.8.12 is a required server plugin. LuigiScreen will not start
without it.**

MediaMTX and OBS are separate applications. LuigiScreen configures and reads
the stream but does not replace either application.

## How it works

```text
OBS or another publisher
          |
          | RTMP video
          v
       MediaMTX
          |
          | RTMP video
          v
 LuigiScreen + FFmpeg
          |
          v
       MapEngine
          |
          v
 Minecraft map screen
```

The RTMP publisher, MediaMTX and Minecraft server can run on the same
computer, across a local network, through a TCP tunnel, or on external
hosting.

## Installation

1. Stop the Paper server completely.
2. Install MapEngine 1.8.12 in the server's `plugins` folder.
3. Put the LuigiScreen JAR in the same `plugins` folder.
4. Start the server.
5. Run `/screen mediamtx <situation>` to generate a MediaMTX setup.
6. Start MediaMTX.
7. Configure OBS to publish to the generated RTMP address.
8. Look at the upper-left block of a vertical wall.
9. Create and start the screen.

Example for a 7x4 screen:

```text
/screen mediamtx same-pc
/screen create main 7 4
/screen start main
```

Do not use plugin hot-reload tools when replacing LuigiScreen. Stop the Java
server process completely because native FFmpeg libraries remain loaded until
shutdown.

## Commands

| Command | Description |
| --- | --- |
| `/screen create <name> [width] [height]` | Create a named screen on the targeted wall |
| `/screen clone <source> <new-name>` | Clone a screen and share its source URL |
| `/screen list` | List every screen |
| `/screen start <name\|all>` | Enable one or every screen |
| `/screen stop <name\|all>` | Disable screens without deleting them |
| `/screen remove <name>` | Remove one screen |
| `/screen status [name]` | Show registry or detailed screen state |
| `/screen set <name> <url\|fps\|distance\|enabled\|permission> <value>` | Update one screen |
| `/screen reload` | Reload configuration without destroying screens |
| `/screen debug` | Toggle live performance statistics |
| `/screen mediamtx <situation>` | Generate a guided MediaMTX configuration |

`luigiscreen.admin` is granted to operators and includes every command.
Individual permissions such as `luigiscreen.create`, `luigiscreen.remove`,
`luigiscreen.start`, `luigiscreen.stop` and `luigiscreen.status` can be
assigned separately. Screens are public by default and may optionally require
`luigiscreen.see.<screen-name>`.

Available MediaMTX situations:

```text
same-pc
lan
internet
vpn
hosting
```

## Performance and safety

The default public configuration limits each screen to `10x6` maps and 60
maps. Adaptive FPS, viewer pause and map-update limits help protect the
server and connected clients.

Screens with the same URL share one FFmpeg decoder and decoded image. Each
screen still has its own MapEngine rendering and packet cost.

Large displays can still consume significant CPU, memory and network
bandwidth. Test `/screen debug` with real players before increasing any safety
limit.

LuigiScreen masks common passwords and stream keys in its own logs and status
commands. Server administrators must still remove credentials and private
addresses before sharing complete configuration files or logs.

## Current alpha limitations

- Video only; stream audio is not played in Minecraft
- No ARM or macOS native libraries
- No Folia support
- No built-in MediaMTX process manager
- No built-in OBS replacement
- A live desktop stream ends when its publishing computer is turned off

## Alpha notice

LuigiScreen is currently alpha software.

Back up the server before installing or updating it. Test new versions away
from production and report reproducible problems with sanitized logs.

This CurseForge project contains LuigiScreen Free only. A future paid
LuigiScreen Plus edition is not part of this alpha release or download.

## License

LuigiScreen Free is copyright (C) 2026 unknown-566 and licensed under the GNU
Affero General Public License v3.0 only.

Modified builds must preserve the required copyright and license notices.
Unofficial builds must use different branding and must not present themselves
as official LuigiScreen releases.

## Links

- Documentation: https://unknown-56-works.gitbook.io/luigiscreen/
- Source code: https://github.com/unknown-566/LuigiScreen
- Issues: https://github.com/unknown-566/LuigiScreen/issues
- License: https://github.com/unknown-566/LuigiScreen/blob/main/LICENSE
- Third-party notices: https://github.com/unknown-566/LuigiScreen/blob/main/THIRD_PARTY_NOTICES.md
````

## Current uploaded file

**Display name:**

```text
LuigiScreen 1.1.0-alpha.11
```

**File:**

```text
LuigiScreen-1.1.0-alpha.11.jar
```

**Release type:** Alpha

**Supported game version:** Minecraft 1.21.11

**Platform tag:** Bukkit

If the upload form offers Paper separately, select Paper. Otherwise use the
Bukkit plugin platform and state Paper support in the description.

**Java version:** Java 21

**File size:** 55,252,755 bytes

**SHA-256:**

```text
085A090EF2AF27FF93A72F0EA6A034A98983D9672B613436F8FF1E22422D519F
```

## File dependency

MapEngine 1.8.12 is required.

If MapEngine appears in the CurseForge dependency picker:

```text
Dependency type: Required
Project: MapEngine
```

If it does not appear, do not select an unrelated project and do not upload
MapEngine yourself. Keep the requirement clearly visible in the project
description and release notes.

Do not include a direct external JAR download link in the CurseForge
description. CurseForge moderation does not allow external file download
links.

## Current file changelog

Paste this into the file changelog:

```markdown
## LuigiScreen 1.1.0-alpha.11

### Features

- Fixed `/screen reload` removing MapEngine displays
- Reload never destroys an existing MapEngine display
- Screens missing from config are restored; use `/screen remove` to delete them
- Geometry edits are kept safe until the screen is explicitly removed and recreated
- URL, FPS, distance, enabled, permission and rendering settings update in place
- MediaMTX source changes use the same non-destructive screen reconciliation
- Fixed viewer respawning when the glowing setting changes
- Corrected the plugin author to `unknown_56`
- 40 automated tests
- Windows x86_64 and Linux x86_64 FFmpeg natives

### Requirements

- Paper 1.21.11
- Java 21
- MapEngine 1.8.12
- MediaMTX
- OBS Studio or another RTMP publisher

### Known limitations

- No stream audio in Minecraft
- No ARM, macOS or Folia support
- MediaMTX and an RTMP publisher run separately
- Every screen still adds MapEngine render and packet cost

This is alpha software. Back up the server and test it away from production.
```

## Relations tab

After the project is approved, open **Relations** or edit the uploaded file.

Add MapEngine as:

```text
Required dependency
```

Only do this if the real MapEngine project is available in CurseForge search.
Do not select a similarly named project without verifying its author and
description.

## Gallery

CurseForge moderation expects the page itself to explain the plugin, but a
gallery still helps users verify the result.

Recommended real screenshots:

1. `Live 7x4 screen`
   Caption: `An OBS video stream rendered live on a 7x4 Minecraft map wall.`
2. `OBS and Minecraft`
   Caption: `The OBS preview and matching in-game LuigiScreen display.`
3. `Performance debug`
   Caption: `LuigiScreen boss bar and sidebar statistics during an active stream.`
4. `Large display`
   Caption: `A larger map screen using adaptive FPS and update limits.`
5. `MediaMTX wizard`
   Caption: `The guided MediaMTX setup without passwords or public addresses.`

Every screenshot must:

- Show the real plugin
- Hide IP addresses, usernames where needed, passwords and stream keys
- Avoid copyrighted video content
- Avoid chat messages unrelated to the plugin
- Avoid AI-generated or AI-enhanced representations of plugin behavior

## Moderation notes

CurseForge expects all essential functional information directly on the
project page. The wiki link supplements the description but does not replace
it.

Keep external project, source and documentation links at the bottom of the
description. Do not include:

- External JAR download links
- Modrinth download links
- Paid Plus advertisements
- Donation promotions in the alpha description
- Minecraft version numbers in the project name
- Reuploads without meaningful changes

## Submission checklist

- [ ] Project type is Bukkit Plugins
- [ ] Project name is exactly `LuigiScreen`
- [ ] Summary is one clear English sentence
- [ ] Description contains the full functional information
- [ ] Avatar is an original 400x400 PNG
- [ ] License is GNU Affero General Public License v3.0
- [ ] Source URL points to the public Free repository
- [ ] Issue tracker points to the LuigiScreen GitHub repository
- [ ] Wiki points to the public GitBook site
- [ ] First file is marked Alpha
- [ ] Minecraft 1.21.11 is selected
- [ ] MapEngine 1.8.12 is clearly marked as required
- [ ] No external file download links are present
- [ ] No passwords, stream keys or private addresses are present
- [ ] The uploaded JAR matches the documented SHA-256
- [ ] Gallery images show real plugin behavior
- [ ] Free and future Plus editions are not presented as the same download
