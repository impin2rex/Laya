#!/usr/bin/env bash
# Builds an unsigned (ad-hoc signed) Laya IPA for sideloading.
# Usage: scripts/build-ios-ipa.sh 1.9.6
set -euo pipefail
V="${1:?usage: $0 <version>}"
cd "$(dirname "$0")/.."
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"

# Link the Kotlin framework first with a bigger heap: the release link OOMs at
# the 4 GB set in gradle.properties. Xcode's Gradle step then finds it up to date.
./gradlew --stop >/dev/null
./gradlew :sharedUi:linkReleaseFrameworkIosArm64 \
  -Dorg.gradle.jvmargs="-Xmx6g -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true"

xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release \
  -sdk iphoneos -destination 'generic/platform=iOS' -derivedDataPath build/dd \
  MARKETING_VERSION="$V" CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" build

APP=build/dd/Build/Products/Release-iphoneos/Laya.app
find "$APP" -type d -empty -delete
for f in "$APP"/Frameworks/*.framework; do codesign --force --sign - --timestamp=none "$f"; done
codesign --force --sign - --timestamp=none --generate-entitlement-der \
  --entitlements iosApp/LayaWidgets/LayaWidgets.entitlements "$APP/PlugIns/LayaWidgets.appex"
codesign --force --sign - --timestamp=none --generate-entitlement-der \
  --entitlements iosApp/iosApp/iosApp.entitlements "$APP"

rm -rf build/ipa && mkdir -p build/ipa/Payload && cp -R "$APP" build/ipa/Payload/
(cd build/ipa && zip -qry "../Laya-$V-ios.ipa" Payload)
echo "Built build/Laya-$V-ios.ipa"
