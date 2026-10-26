#!/bin/sh
# Google publishes the Cast iOS SDK only as a zip (no Swift package), so fetch the official one,
# pinned and checksummed, into iosApp/Vendor/. Wired as XcodeGen's preGenCommand, so local builds and CI both run it.
set -eu
VERSION=4.8.6
SHA256=55f6c21291a1315c68063f07e7d76225564bff70f2fd38caad135c71d66eb310
cd "$(dirname "$0")/.."
[ -f Vendor/GoogleCast.xcframework/.version-$VERSION ] && exit 0
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
curl -fsSL -o "$tmp/cast.zip" "https://dl.google.com/dl/chromecast/sdk/ios/GoogleCastSDK-ios-${VERSION}_dynamic.zip"
echo "$SHA256  $tmp/cast.zip" | shasum -a 256 -c - >/dev/null
unzip -q "$tmp/cast.zip" -d "$tmp/x"
rm -rf Vendor && mkdir -p Vendor
mv "$tmp/x/GoogleCastSDK-ios-${VERSION}_dynamic_xcframework/GoogleCast.xcframework" Vendor/
touch Vendor/GoogleCast.xcframework/.version-$VERSION
