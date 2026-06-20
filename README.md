# LuigiScreen

LuigiScreen Free is a server-side Paper/Bukkit plugin that displays live
streams, videos, images and GIFs on configurable walls of Minecraft maps.

Author: **unknown_56**

LuigiScreen loads the selected media source, decodes its latest frame and
renders it through MapEngine. Players do not need a client mod.

> LuigiScreen is currently alpha software. Back up the server and test new
> builds away from production.

## Features

- In-game Control Studio opened with `/screen menu`
- Browser Web Studio opened securely with `/screen web`
- One-time login links, revocable sessions, CSRF protection and role-based API access
- Preview/Program Live Studio with bounded live source thumbnails
- Contextual `i` help for Web Studio settings, metrics and controls
- Dashboard, screen details, Live Control Room and diagnostics
- Media Library with automatic folder watching, validation and map thumbnails
- Draft and Publish editing with config snapshots and one-click undo
- Content queues, screen groups, schedules, templates and audience voting
- Per-role permissions for every Control Studio section and action
- Playback explanations, eligibility diagnostics and aggregate usage statistics
- RTMP and MJPEG live streams
- Looping local videos and GIFs
- Local and URL images
- Per-screen playlists with weighted random media rotation
- Manual event scenes that temporarily interrupt normal playback
- Random media folders for videos, images and GIFs
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
- Asynchronous Modrinth update notifications for the console and authorized players
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
| `/screen menu` | Open LuigiScreen Control Studio |
| `/screen web [open\|status\|revoke]` | Open, inspect or revoke Web Studio sessions |
| `/screen create <name> [width] [height]` | Create a named map screen |
| `/screen clone <source> <new-name>` | Create another screen that shares its typed source |
| `/screen list` | List all screens and masked source values |
| `/screen start <name\|all>` | Enable one or every screen |
| `/screen stop <name\|all>` | Disable streaming without deleting screens |
| `/screen remove <name>` | Remove one screen |
| `/screen status [name]` | Show the registry summary or detailed screen state |
| `/screen source <name> <type> <value>` | Switch RTMP, MJPEG, video, image, URL image or GIF |
| `/screen playlist list|set|clear ...` | Assign or clear a configured media playlist |
| `/screen event list|play|stop ...` | Play or stop a configured manual event scene |
| `/screen set <name> <url\|fps\|distance\|enabled\|permission> <value>` | Change an independent screen setting |
| `/screen reload` | Reload configuration and localization without destroying screens |
| `/screen debug` | Toggle live performance statistics |
| `/screen mediamtx <situation>` | Generate a MediaMTX configuration |
| `/screen vote <screen> <option>` | Vote for the next media item |
| `/screen vote start\|status\|end <screen> [options...]` | Manage an audience vote |

`luigiscreen.admin` is granted to operators and includes every command plus
access to all protected screens. Individual permissions include
`luigiscreen.create`, `luigiscreen.remove`, `luigiscreen.start`,
`luigiscreen.stop`, `luigiscreen.status` and equivalent nodes for the other
commands.

`luigiscreen.update` receives a clickable notification when a newer public
version is available on Modrinth. It defaults to operators and is included in
`luigiscreen.admin`. The asynchronous checker can be configured or disabled
under `updates:` in `config.yml`.

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

The current suite contains 64 automated tests covering:

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
- Duration parsing for playlist and event timing
- Zero-copy MapEngine render-surface wrapping
- Semantic alpha, beta and release version ordering
- Modrinth version-feed selection
- Control Studio language and permission resources
- media thumbnail generation
- Web Studio JSON serialization, login security and bundled resources

## Platform limits

The current artifact supports Windows x86_64 and Linux x86_64. It does not
support ARM, macOS or Folia.

The default per-screen limit is 10x6 maps and 60 maps. Larger screens can
consume substantial CPU, memory and network bandwidth.

Each screen stores its own `source.type`, `source.value`, `playlist`, `fps`,
`distance`, `world`, `location`, `width`, `height`, `enabled` and
`permission-required` value. Screens with the exact same active source type and
value automatically use one loader and independently render the shared latest
frame.

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

## Control Studio

Use `/screen menu` to open the in-game administration interface. The dashboard
shows running screens, errors, active events, viewers and indexed media.

Control Studio includes:

- per-screen Now Playing, reason, next item, timing, health and location tools
- hold, resume, skip, repeat, return-to-automation and queue controls
- a Live Control Room for cueing media, events and emergency mode
- a watched Media Library with generated map thumbnails
- playlist probability simulation and current eligibility explanations
- event timelines with media, wait, manual, command, broadcast, sound, title
  and screen-group steps
- screen groups, recurring schedules, conflict warnings and templates
- Draft and Publish changes, config snapshots, audit history and undo
- audience voting with permission and distance checks
- aggregate play, viewer-time, skip and failure statistics

Generated studio state is stored in `plugins/LuigiScreen/studio.yml`. Cached
thumbnails are stored under `plugins/LuigiScreen/media/.thumbnails/`, and
pre-publish backups are kept in `plugins/LuigiScreen/history/`.

## Web Studio

Use `/screen web` to create a short-lived, one-time browser login. The embedded
server listens on `127.0.0.1:8765` by default and provides dashboards, screen
details, Media Library, playlist and event editors, Preview/Program Live
Studio, schedules, groups, monitoring, diagnostics and structured drafts.

Every property and field includes contextual `i` help. Live state uses a
compact server-sent event stream, while preview capture is downscaled,
rate-limited and inactive when no browser is connected.

Do not expose the HTTP port directly to the internet. Remote use requires an
HTTPS reverse proxy or authenticated VPN. See the complete
[Web Studio guide](https://unknown-56-works.gitbook.io/luigiscreen/studio/web-studio).

## Playlists and events

Playlists and events are defined in `config.yml`.

```yaml
playlists:
  spawn_rotation:
    history-window: 3
    category-history-window: 1
    items:
      stream:
        type: rtmp
        value: "rtmp://127.0.0.1:55556/screen"
        weight: 10
        duration: 60s
      random_media:
        type: folder
        folder: trailers
        media-types: [video, image, gif]
        weight: 3
        duration: 20s
        cooldown: 2m
        category: trailers
        guaranteed-after: 30m

events:
  update_reveal:
    sequence:
      title:
        type: text
        text: "Update starts soon"
        duration: 5s
      trailer:
        type: video
        value: "trailers/update.mp4"
        duration: 30s
      operator:
        type: wait-manual
        text: "Waiting for operator"
        duration: 1s
```

Use them with:

```text
/screen playlist set main spawn_rotation
/screen event play main update_reveal
/screen event stop main
```

When two screens select the same active source, LuigiScreen still shares one
loader. Text event steps render an internal LuigiScreen frame and then return to
the configured playlist or fallback source.

The Media Library watches local folders automatically. New, changed and removed
files are validated and reflected in the GUI without `/screen reload`.

## Runtime performance

- MapEngine render surfaces are reused instead of reconstructed for every frame.
- Delta buffers are allocated once per screen and updated in place.
- Player locations are captured once per viewer refresh and shared by all screens.
- Screens sharing a source use one decoder or image loader.
- Static image workers sleep until stopped after their frame is loaded.
- `/screen start all` and `/screen stop all` save the config once per batch.
- Decoder shutdown waits longer than the configured remote I/O timeout.

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
