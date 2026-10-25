# Player components

Since 0.5.0 the Windows artifact bundles libmpv from
[shinchiro's 20261021 build](https://github.com/shinchiro/mpv-winbuild-cmake/releases/tag/20261021),
a Windows build distributor listed on [mpv's installation page](https://mpv.io/installation/).
The baseline x86_64 archive is pinned to SHA-256
`efc719eff3dc5954b4a5efc800632808bcec01cc9034940dd14e5eb7fe9ea633`.
It does not require the additional CPU instructions of x86_64-v3 builds.

mpv source is pinned to [e471496c56](https://github.com/mpv-player/mpv/tree/e471496c56).
Upstream Copyright, LICENSE.GPL, LICENSE.LGPL, source/build links and archive
contents are retained under the packaged `mpv` resource directory. The build
scripts identify FFmpeg/libass/libplacebo and other dependencies. This is a GPL
build; public distribution must account for its component license terms. A
project-wide release license has not yet been selected.

The app calls the libmpv client ABI directly through JNA. VLC and vlcj are no
longer included. mpv has no external application installation requirement.

These components are used by the Windows player. Telegram runtime notices are
also included by the TDLib build script. No provider credentials are embedded
in any native runtime or build artifact.
