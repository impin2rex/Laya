# Laya Desktop

Laya has a Kotlin Multiplatform shared module and a Compose Multiplatform
desktop application for macOS, Linux, and Windows. Desktop uses the JVM target, which is
the supported Compose Multiplatform desktop model.

The desktop target uses Java 21.

On macOS (Apple Silicon or Intel), run it with a Java 21 JDK available in your environment:

```bash
./gradlew :desktopApp:run
```

On Linux, run it with the JDK and native libraries available in the shell:

On NixOS:
```bash
nix-shell -p jdk21 libglvnd glib gtk3 pango atk cairo cmake gdk-pixbuf libXtst libXxf86vm alsa-lib ffmpeg_6 --run '\
  export LD_LIBRARY_PATH="$(nix eval --raw nixpkgs#libglvnd.outPath)/lib:$(nix eval --raw nixpkgs#glib.out)/lib:$(nix eval --raw nixpkgs#gtk3.outPath)/lib:$(nix eval --raw nixpkgs#pango.out)/lib:$(nix eval --raw nixpkgs#atk.outPath)/lib:$(nix eval --raw nixpkgs#cairo.outPath)/lib:$(nix eval --raw nixpkgs#gdk-pixbuf.outPath)/lib:$(nix eval --raw nixpkgs#libXtst.outPath)/lib:$(nix eval --raw nixpkgs#libXxf86vm.outPath)/lib:$(nix eval --raw nixpkgs#alsa-lib.outPath)/lib:$(nix eval --raw nixpkgs#ffmpeg_6.lib)/lib:$HOME/.openjfx/cache/21.0.6+3/amd64:/run/opengl-driver/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"; \
  bash ./gradlew :desktopApp:run
'
```

On Windows, use a Java 21 shell or install and select a Java 21 JDK before
running `gradlew.bat :desktopApp:run`.

## Installing a release

Every download on the [releases page](https://github.com/impin2rex/Laya/releases) carries its own Java
runtime, the FFmpeg and ONNX natives, the Automix analyser and its models.
Nothing else has to be installed first — no JDK, no codec pack.

| Platform | Download | Notes |
|---|---|---|
| macOS | `Laya-<version>-macos-arm64.dmg` | For Apple Silicon Macs (M1/M2/M3/M4/later). Drag to Applications. |
| macOS | `Laya-<version>-macos-x64.dmg` | For Intel Macs. Drag to Applications. |
| macOS | `Laya-<version>-macos-*.pkg` | Standard macOS installer package for automated or managed installations. |
| Linux | `Laya-<version>-linux-x86_64.AppImage` | `chmod +x` it and run. No install, works on any distribution. |
| Linux | `Laya-<version>-linux-amd64.deb` | `sudo apt install ./Laya-*.deb` on Debian, Ubuntu and derivatives. |
| Linux | `Laya-<version>-linux-x86_64.rpm` | `sudo dnf install ./Laya-*.rpm` on Fedora, RHEL and openSUSE. |
| Windows | `Laya-<version>-windows-x64-setup.exe` | The ordinary installer. Installs for the current user, so it never asks for an administrator. |
| Windows | `Laya-<version>-windows-x64.msi` | The same thing for anyone who deploys by MSI. |
| Windows | `Laya-<version>-windows-x64-portable.zip` | Unzip anywhere and run `Laya.exe`. Writes nothing outside the folder. |

**macOS Gatekeeper.** Unsigned binaries built locally or downloaded outside the Mac App Store
may trigger a macOS security prompt ("Laya is damaged and can't be opened" or "unidentified developer").
To clear the quarantine flag, run:

```bash
xattr -cr /Applications/Laya.app
```

The Linux packages are built on Ubuntu 22.04 against its glibc, so they install
on that release and anything newer.

**Linux system libraries.** The packages carry their own Java runtime and codecs
but not the desktop's own graphics stack, and jpackage cannot derive that list —
so the deb and rpm declare only `xdg-utils`. Any machine running a desktop
session already has what is needed (GTK 3, X11 or XWayland, GL, ALSA); a minimal
or headless install does not, and will fail at startup rather than at install
time.

**Windows and the Visual C++ runtime.** The Automix analyser is linked against a
static C runtime, so on almost every machine there is nothing to install. If
Laya starts but Automix never analyses anything, install the
[Microsoft Visual C++ 2015–2022 Redistributable (x64)](https://aka.ms/vs/17/release/vc_redist.x64.exe)
and restart it — that is the one dependency the bundled runtime cannot carry
itself, because Windows expects it to be a system component.

## Building the packages yourself

jpackage builds a package by driving the target platform's own tooling, so each
one has to be built on the platform it is for. This is what CI does; see
[`.github/workflows/release.yml`](.github/workflows/release.yml).

On macOS:

```bash
# Builds the disk image (.dmg) installer
./gradlew :desktopApp:packageDmg

# Builds the .pkg installer
./gradlew :desktopApp:packagePkg

# Standalone Laya.app bundle
./gradlew :desktopApp:createDistributable
# Output is at: desktopApp/build/compose/binaries/main/app/Laya.app
```

On Linux — `rpm`, `fakeroot` and `binutils` must be installed for the packages,
and `file` for the AppImage:

```bash
bash ./gradlew :desktopApp:packageDeb
bash ./gradlew :desktopApp:packageRpm

# The AppImage is wrapped around the app image jpackage produces.
bash ./gradlew :desktopApp:createDistributable
desktopApp/packaging/appimage.sh \
  desktopApp/build/compose/binaries/main/app/Laya \
  dist/Laya-linux-x86_64.AppImage
```

On Windows — [WiX Toolset 3](https://github.com/wixtoolset/wix3/releases) must
be on `PATH`, which is what jpackage builds both the `.exe` and the `.msi` with:

```bat
gradlew.bat :desktopApp:packageExe
gradlew.bat :desktopApp:packageMsi

REM The portable build is the app image, zipped.
gradlew.bat :desktopApp:createDistributable
```

Pass `-Playa.version=1.2.3` to stamp a version other than the one in
`desktopApp/build.gradle.kts`; a release build takes it from the tag.
