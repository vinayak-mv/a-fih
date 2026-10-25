# Player components

The unsigned Windows testing artifact includes VLC 3.0.24 from VideoLAN's
[official Windows archive](https://download.videolan.org/pub/videolan/vlc/3.0.24/win64/).
The preparation script verifies the published archive SHA-256. The complete
runtime retains upstream COPYING/license notices beside its DLLs/plugins.
VideoLAN publishes the corresponding
[VLC 3.0.24 source archive](https://download.videolan.org/pub/videolan/vlc/3.0.24/).
LibVLC is LGPL; VLC and plugins include GPL components. Refer to the individual
upstream component notices for their applicable terms.

The app uses [vlcj 4.8.3](https://github.com/caprica/vlcj/tree/vlcj-4.8.3),
a GPL-3.0 Java binding to LibVLC. Its source and license are available at that
pinned tag. Public release licensing/distribution must account for vlcj's GPL
terms; a project-wide release license has not yet been selected.

These components are used by the Windows player. Telegram runtime notices are
also included by the TDLib build script. No provider credentials are embedded
in any native runtime or build artifact.
