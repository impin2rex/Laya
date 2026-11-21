# Vendor/onnxruntime-swift-package-manager

A copy of [`microsoft/onnxruntime-swift-package-manager`][pkg] at tag **1.19.2**
(revision `bbc428e168a0374eb7d0503cdb7c73fdc1d99751`), MIT licensed — `LICENSE`
is included unchanged.

## Why it is here

Microsoft removed `microsoft/onnxruntime` and this package's repository from
GitHub around 17–18 November 2026. No reason was published. At the same time
`download.onnxruntime.ai`, which the package's binary targets fetch from, stopped
serving a valid certificate. Either failure alone breaks the iOS build:

```
fatal: could not read Username for 'https://github.com': terminal prompts disabled
xcodebuild: error: Could not resolve package dependencies
```

Android, TV and desktop are unaffected — they take `com.microsoft.onnxruntime:*`
from Maven Central, which does not delete published artifacts.

## What was changed

Only the two binary target URLs in `Package.swift`, which now point at release
assets on this repository instead of the dead CDN. **The checksums are
Microsoft's own, untouched**, and the re-hosted archives match them:

| Archive | SHA256 |
|---|---|
| `pod-archive-onnxruntime-c-1.19.2.zip` | `28787ee2f966a2c47eb293322c733c5dc4b5e3327cec321c1fe31a7c698edf68` |
| `pod-archive-onnxruntime-extensions-c-0.12.0.zip` | `542be5904cf2cc93db6b8afcd11b023b001333e3151bea8bff1c7b1cf8959fab` |

SwiftPM verifies both on every resolve, so a tampered copy fails the build rather
than shipping. Verify by hand with `shasum -a 256 <file>`.

The archives came from a machine that had resolved the package while the upstream
was still live; no third-party mirror was used. Several community mirrors appeared
after the takedown, re-hosting the binaries under *their own* checksums — those
are deliberately not used here, since a package pulled by its publisher is exactly
the case where someone else's copy should not be trusted on faith.

## Updating

Upstream is gone, so there is no newer version to move to. If Microsoft restores
the repositories, drop this directory and put back in `project.yml`:

```yaml
  onnxruntime:
    url: https://github.com/microsoft/onnxruntime-swift-package-manager
    exactVersion: 1.19.2
```

[pkg]: https://github.com/microsoft/onnxruntime-swift-package-manager
