# Unreleased

- Adds tintable icons. `AssetRef` gains `rendering: AssetRendering = AssetRendering.Original` as its last parameter; `AssetRendering` is `Original` or `Template`.
- Reads an optional `rendering` string on signed placements and variant cells; state members stay ignored. A malformed value (null, a non-string, or anything outside `^[a-z][a-z0-9-]{0,31}$`) rejects the manifest. A well-formed value this client does not know is kept.
- Uses a descriptor only when its rendering equals the reference's (absent means original), checked on the selected appearance or arm cell. A mismatch or unknown value is handled like incompatible dimensions: that release's artwork is never read from the cache or downloaded, retained releases follow the same rule, and with no compatible artwork the result is `BUNDLE`. Other placements are unaffected.
- Code generation reads catalog `rendering`: `"template"` generates `rendering = AssetRendering.Template`, `"original"` or no field generates byte-identical output, and any other value fails.
- No Compose dependency is added: the SDK returns the mask `Bitmap` and the app tints it. The README shows `Icon` with `LocalContentColor`, a VectorDrawable fallback, and `targetPixels` at logical size × density.
- Vendors the shared corpus with 15 rendering manifests (115 signed manifest cases) and 10 rendering resolution cases.

Recompile consuming applications and their generated references when replacing the AAR. The new `AssetRef` parameter keeps Kotlin source call sites compiling, but changes its JVM constructor, `copy`, and component signatures.

Validation on October 9, 2026: codegen tests (3), 102 JVM unit tests with the optional hosted test skipped, and lint passed. No release build, emulator, or device run. See VERIFICATION.md.

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
