# Unreleased

- Adds short looping video. `AssetClient.resolveVideo(ref)` returns `ResolvedVideo` (`source` `REMOTE`, `CACHE` or `POSTER`, `sequence`, `message`, `file`, `width`, `height`, `durationMs`, `frameRate`) for a placement's base slot. `resolve` never downloads a clip. Only the latest accepted release downloads; retained releases are cache-only. As for Lottie animations, a missing current clip returns `POSTER`, and a current clip that cannot be used first recovers from the newest verified cached clip of an earlier release. Every use, including each cache read, checks the signed length and SHA-256 and a bounded header walk (`ftyp` first, `moov` before the first `mdat`, no `moof`, an `avc1` sample entry at the signed size). Any failure returns `POSTER`, and the app shows the ordinary image or its bundled fallback.
- Validates `videoSchemaVersion` and `video`. The version, when present, must be exactly 1 (null or any other value rejects the manifest); `video` without it, on a variant cell, or beside `animation` rejects the manifest. The descriptor must be `mp4` / `h264-loop-v1` / `video/mp4` with a lowercase hex `sha256`, 1–4,194,304 `bytes`, even `width` and `height` of 16–1280 with at most 921,600 pixels in exactly the slot's aspect ratio, integer `durationMs` 1,000–10,000, a finite numeric `frameRate` 1–30, and the exact same-origin path `/api/delivery/{orgId}/{appId}/assets/{assetId}/videos/{sha256}`. `animationSchemaVersion` stays ignored.
- `FileAssetStorage` implements the new `AssetVideoStorage` and keeps clips as `<sha256>.mp4` in `cacheDir`, within the existing 50 MiB / 100-entry budget and least-recently-used eviction. Custom storage without it gets `POSTER`. Adds `Limits.VIDEO_BYTES` (4 MiB) and `AssetVideoSource`.
- `AssetTransport` gains `get(url, maxBytes, accept)`, which defaults to the two-argument call, and the client now names the media type for every request: `application/json`, the candidate's own image type (PNG renditions send `image/png`, not `image/webp`), or `video/mp4`. `HttpsTransport` gains `videoTimeoutMillis` (default 30,000, allowed 20–30,000) for `video/mp4` calls; `HttpsTransport(timeoutMillis)` still compiles.
- The clip download runs outside the client's mutex, so a slow clip does not hold up image resolution. The cache no longer evicts the entry it has just written when modification times tie.
- No player dependency: the README shows Media3 1.11.x in the app. Not verified on an emulator or a device.
- Vendors the shared corpus with 27 video manifests (142 signed manifest cases), `video.json` and the `loop.mp4` clip fixtures.

Recompile consuming applications when replacing the AAR. New public types (`ResolvedVideo`, `AssetVideoSource`, `AssetVideoStorage`), the new `AssetTransport` method and the `HttpsTransport` constructor change JVM signatures.

- Code generation rejects a group or member named `AppAssets`, `AssetRef`, `AssetAccessibility` or `AssetRendering`, as the Swift generator does for its own generated names. `AssetRendering` produced Kotlin that did not compile when the catalog had a template placement; the other three shadowed the generated root object or the imported SDK types.
- Code generation rejects two groups that differ only in case (their class files overwrite each other on case-insensitive file systems, so one object is missing at run time) and two members of one group that compile to the same JVM getter, such as `coast` and `Coast`, which did not compile.
- A package segment that is a Kotlin hard keyword, such as `is` or `fun`, is escaped with backticks; the unescaped `package` line did not compile.
- Any other catalog generates byte-identical output. SDK sources and the AAR are unchanged.

# 0.4.0-preview.1 — October 10, 2026

- Adds tintable icons. `AssetRef` gains `rendering: AssetRendering = AssetRendering.Original` as its last parameter; `AssetRendering` is `Original` or `Template`.
- Reads an optional `rendering` string on signed placements and variant cells; state members stay ignored. A malformed value (null, a non-string, or anything outside `^[a-z][a-z0-9-]{0,31}$`) rejects the manifest. A well-formed value this client does not know is kept.
- Uses a descriptor only when its rendering equals the reference's (absent means original), checked on the selected appearance or arm cell. A mismatch or unknown value is handled like incompatible dimensions: that release's artwork is never read from the cache or downloaded, retained releases follow the same rule, and with no compatible artwork the result is `BUNDLE`. Other placements are unaffected.
- Code generation reads catalog `rendering`: `"template"` generates `rendering = AssetRendering.Template`, `"original"` or no field generates byte-identical output, and any other value fails.
- No Compose dependency is added: the SDK returns the mask `Bitmap` and the app tints it. The README shows `Icon` with `LocalContentColor`, a VectorDrawable fallback, and `targetPixels` at logical size × density.
- Vendors the shared corpus with 15 rendering manifests (115 signed manifest cases) and 10 rendering resolution cases.

Recompile consuming applications and their generated references when replacing the AAR. The new `AssetRef` parameter keeps Kotlin source call sites compiling, but changes its JVM constructor, `copy`, and component signatures.

Validation on October 9 and 10, 2026: codegen tests (3), 102 JVM unit tests with the optional hosted test skipped, lint, and the release build passed (`./gradlew :sdk:testDebugUnitTest :sdk:lint :sdk:assembleRelease`, JDK 17). `assetlib-android-0.4.0-preview.1.aar` SHA-256 `03d55eaa1975fe86494fb2f65e69e0ad7ad3f5a60d492f02d3c06bf3bdc9a98a` (macOS build; CI on the tag uploads a Linux build for comparison). No emulator or device run. See VERIFICATION.md.

# 0.3.1-preview.1 — October 9, 2026

- Rejects public configuration JSON over 4096 UTF-8 bytes, including unknown fields; the previous limit was 8192 bytes.
- Rejects duplicate exact-PEM pins and sets outside 1–16 keys. Every pin remains an Ed25519 SPKI PEM of at most 256 UTF-8 bytes.
- Rejects `keyId` without an explicit `pinnedPublicKey`, including when the ID matches the first set member. Known fields reject explicit nulls; optional `keyIds` must match the pin IDs in length and order. A single pin supplied alongside a set must belong to that set.
- Runs all 43 generated shared public-config cases and 39 pinned-envelope expectations, including a trusted set member other than the single pin and an untrusted signer. Set-only configurations remain supported.
- Keeps accepted configurations within the JSON bound when serialized again. Near the limit, `toJson()` omits redundant derived IDs and the duplicate first pin while preserving every ordered trusted pin and any explicit non-first single pin.
- Restores the instrumented `AccessibilitySemanticsTest`, its debug-only host activity and manifest, and the instrumentation `targetSdk` setting, which were missing from `main` when 0.3.0-preview.1 was tagged. Test-only; release sources are unchanged.
- The CI artifact is named `assetlib-android-release` instead of carrying a stale version.

Validation on October 9, 2026: codegen tests (2), 98 JVM unit tests with the optional hosted test skipped, lint, and the release build passed. See VERIFICATION.md.

Documentation fix on `main`, October 9, 2026, after the release was published: the README install section still said the patch was untagged and unpublished. It now points straight at the published release. No source or AAR change.

# 0.3.0-preview.1

- Accepts a `staging` environment in the public configuration with the environment-scoped manifest path.
- Validates the additive `variantSchemaVersion: 1` extension with every rule of the shared contract: declared appearance and arm values, no duplicate coordinates, cell images checked like slot images, cell states ignored.
- Resolves appearance and arm cells in the fixed order arm plus appearance, arm only, appearance only, then the legacy cell, never borrowing across arms; resolved assets report `appearance`, `arm`, and `armSource`; cache entries key on the selected cell.
- Adds an optional `decide` callback for the arm, run outside the mutex with a bounded wait; afterwards the client resyncs durable state, returns the bundled result when storage fails to revalidate, and reconciles against the current accepted release before any selection or download.
- Derives the storage namespace from origin, org, app, and environment only, with a one-time verified migration from the previous formula.
- Carries the 0.2.1-preview.1 accessibility source.

Validation: 54 JVM tests with one optional hosted test skipped, lint clean, against the shared corpus of 100 signed manifest cases and 18 resolution entries.

# 0.2.1-preview.1

- Adds optional localized content descriptions to signed placements. Rejects malformed present metadata; manifests without descriptions retain their existing behavior.
- Pairs descriptions with the actual resolved release through download, cache, retained historical fallback, and offline restart. Bundled references carry separately declared `bundledAccessibility`; undescribed remote artwork never borrows it.
- Adds pure locale selection: exact match ignoring case, progressively fewer subtags, then the declared default. The app supplies the language tag and retains control of decoration, contextual labels, and action semantics.
- Generates optional bundled descriptions offline with Kotlin string interpolation escaped. No Compose dependency, automatic label attachment, or new runtime dependency is added.

Recompile consuming applications and their generated references when replacing the AAR. The optional `AssetRef` and `ResolvedAsset` constructor fields preserve Kotlin source call sites, but change their JVM constructor signatures.

Release boundary: 82 shared signed fixtures (65 existing cases and 17 accessibility cases). Manual TalkBack acceptance is not established. The tag sits on a branch that is not an ancestor of `main`; `0.3.0-preview.1` carries the same source changes.

# 0.2.0-preview.1

- Recognizes the signed rendition-schema-1 extension without breaking legacy WebP manifests. Rejects malformed or unknown explicit extensions, including null arrays and versions.
- Selects PNG/WebP according to an explicit target pixel size; defaults to logical reference dimensions. Deterministic area/byte/hash ordering, bounded per-candidate downloads, then legacy WebP fallback. Historical candidates remain cache-only.
- Validates actual raster MIME, declared rendition dimensions, full native decode, hash/byte length, and aspect/pixel bounds before caching or rendering. Returns selected MIME, pixel dimensions, asset ID, hash, source, and rendered release sequence.
- Preserves origin/path binding, pinned signatures, durable high-water marks, same-sequence equivocation checks, concurrent storage guards, and corrupt-state fail-closed behavior.
- SVG candidates are validated but skipped on Android. No native SVG renderer or Compose dependency is added; the demo uses a normal BitmapPainter adapter.

API change: `AssetClient`'s custom decoder now returns `AssetImageInfo?` instead of using the earlier Boolean `validateImage` hook. Default native decoding is enabled even when constructing `AssetClient` directly. Apps using the standard factory need no custom decoder.

Validation: 65 shared signed manifest cases; target ranking, PNG metadata, candidate failure, legacy fallback, historical cache-only and anti-replay unit checks; native PNG/WebP decode and offline restart on Android API 36.1 emulator. See the test reports for exact run counts. Preview status remains; no all-device performance or production support guarantee.
