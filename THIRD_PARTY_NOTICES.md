# Third-Party Notices

LuigiScreen Free includes or interoperates with the components below.
Copyright in each component remains with its respective authors.

## MapEngine 1.8.12

- Purpose: Required Paper plugin and map rendering API
- Bundled: No
- License: GNU Affero General Public License v3.0 only
- Project: https://github.com/MinceraftMC/MapEngine
- License text: `third-party-licenses/MapEngine-AGPL-3.0.txt`

LuigiScreen declares MapEngine as a required runtime dependency. Server
administrators must install it separately.

## JavaCV 1.5.12

- Purpose: Java frame grabbing and conversion API
- Bundled: Yes
- License: Apache License 2.0 or GPLv2-or-later with Classpath exception
- Project: https://github.com/bytedeco/javacv/tree/1.5.12
- License text: `third-party-licenses/JavaCV-LICENSE.txt`

LuigiScreen uses JavaCV under the Apache License 2.0 option.

## JavaCPP 1.5.12

- Purpose: Native library loading and Java/native interoperability
- Bundled: Yes
- License: Apache License 2.0 or GPLv2-or-later with Classpath exception
- Project: https://github.com/bytedeco/javacpp/tree/1.5.12
- License text: `third-party-licenses/JavaCPP-LICENSE.txt`

LuigiScreen uses JavaCPP under the Apache License 2.0 option.

## JavaCPP Presets for FFmpeg 7.1.1-1.5.12

- Purpose: Java bindings and native FFmpeg artifacts
- Bundled: Yes
- License: Apache License 2.0 or GPLv2-or-later with Classpath exception for
  the preset wrapper code; FFmpeg has its own license
- Project: https://github.com/bytedeco/javacpp-presets/tree/1.5.12/ffmpeg
- License text: `third-party-licenses/JavaCPP-Presets-LICENSE.txt`

## FFmpeg 7.1.1

- Purpose: RTMP demuxing and H.264 video decoding
- Bundled: Windows x86_64 and Linux x86_64 native libraries
- Artifact: Standard JavaCPP Presets FFmpeg artifact without the `-gpl`
  classifier
- License: GNU Lesser General Public License v2.1 or later for this build
- Upstream source: https://github.com/FFmpeg/FFmpeg/tree/n7.1.1
- Preset build source: https://github.com/bytedeco/javacpp-presets/tree/1.5.12/ffmpeg
- License text: `third-party-licenses/FFmpeg-LGPL-2.1.txt`
- Legal information: https://ffmpeg.org/legal.html

LuigiScreen does not claim ownership of FFmpeg. Recipients may replace or
rebuild the separately loaded native FFmpeg libraries, subject to platform
compatibility and the applicable licenses.

## Development-only dependencies

Paper API, MapEngine API and JUnit are used while compiling or testing and are
not shaded into the LuigiScreen release JAR unless explicitly stated above.
