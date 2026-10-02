#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="$ROOT/build/swift-build"
VLC="$ROOT/build/vlc-sdk"
VLC_VERSION="3.0.24"
SOURCE="$ROOT/build/vlc-download/vlc-$VLC_VERSION.tar.xz"
VERSION=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$ROOT/macos/Info.plist")
OUTPUT="$ROOT/build/SubPlayer-Mac-$VERSION.app"
ZIP="$ROOT/build/SubPlayer-Mac-$VERSION.zip"
ICONSET="$ROOT/build/SubPlayer.iconset"

if [[ ! -f "$VLC/lib/libvlc.dylib" || ! -d "$VLC/plugins" || ! -f "$SOURCE" ]]; then
    printf '缺少 VLC %s SDK 或源码许可包，请先准备 build/vlc-sdk 和 build/vlc-download。\n' "$VLC_VERSION" >&2
    exit 1
fi
if [[ -e "$OUTPUT" || -e "$ZIP" ]]; then
    printf '目标产物已存在，请先移动旧文件以免覆盖：%s\n' "$OUTPUT" >&2
    exit 1
fi
lipo "$VLC/lib/libvlc.dylib" -verify_arch arm64
(cd "$ROOT/build/vlc-download" && shasum -a 256 -c "vlc-$VLC_VERSION.tar.xz.sha256")

cd "$ROOT/macos"
swift build -c release --product SubPlayerMac --scratch-path "$SDK"
STAGING=$(mktemp -d "$ROOT/build/mac-package.XXXXXX")
trap 'rm -rf "$STAGING"' EXIT
APP="$STAGING/SubPlayer-Mac-$VERSION.app"
RUNTIME="$APP/Contents/Frameworks/VLC"
LICENSES="$APP/Contents/Resources/Licenses/VLC"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources" "$RUNTIME/plugins" "$LICENSES" "$ICONSET"
cp "$SDK/release/SubPlayerMac" "$APP/Contents/MacOS/SubPlayerMac"
cp "$ROOT/macos/Info.plist" "$APP/Contents/Info.plist"
ditto "$VLC/lib" "$RUNTIME/lib"
ditto "$VLC/share" "$RUNTIME/share"
for plugin in "$VLC/plugins/"*.dylib; do
    case "$(basename "$plugin")" in
        libmacosx_plugin.dylib|libosx_notifications_plugin.dylib) continue ;;
    esac
    cp -p "$plugin" "$RUNTIME/plugins/"
done
for name in COPYING COPYING.LIB AUTHORS; do
    tar -xOf "$SOURCE" "vlc-$VLC_VERSION/$name" > "$LICENSES/$name"
done
cp "$ROOT/macos/ThirdPartyNotices.txt" "$APP/Contents/Resources/ThirdPartyNotices.txt"

for size in 16 32 128 256 512; do
    sips -s format png -z "$size" "$size" "$ROOT/icon_source.png" \
        --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
    double=$((size * 2))
    sips -s format png -z "$double" "$double" "$ROOT/icon_source.png" \
        --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/SubPlayer.icns"
while IFS= read -r -d '' library; do
    codesign --force --sign - "$library"
done < <(find "$RUNTIME/lib" "$RUNTIME/plugins" -type f -name '*.dylib' -print0)
codesign --force --sign - "$APP"
codesign --verify --deep --strict "$APP"
plutil -lint "$APP/Contents/Info.plist"
ditto -c -k --keepParent "$APP" "$STAGING/SubPlayer-Mac-$VERSION.zip"
unzip -tq "$STAGING/SubPlayer-Mac-$VERSION.zip"
mv "$APP" "$OUTPUT"
mv "$STAGING/SubPlayer-Mac-$VERSION.zip" "$ZIP"
printf 'App: %s\nZIP: %s\n' "$OUTPUT" "$ZIP"
