# Unreleased

- Restores the instrumented `AccessibilitySemanticsTest`, its debug-only host activity and manifest, and the instrumentation `targetSdk` setting, which were missing from `main` when 0.3.0-preview.1 was tagged. Test-only; release sources are unchanged.
- The CI artifact is named `assetlib-android-release` instead of carrying a stale version.

# 0.3.1-preview.1 — 2026-10-09 (unreleased)

- Rejects public configuration JSON over 4096 UTF-8 bytes, including unknown fields; the previous limit was 8192 bytes.
- Rejects duplicate exact-PEM pins and sets outside 1–16 keys. Every pin remains an Ed25519 SPKI PEM of at most 256 UTF-8 bytes.
- Rejects `keyId` without an explicit `pinnedPublicKey`, including when the ID matches the first set member. Known fields reject explicit nulls; optional `keyIds` must match the pin IDs in length and order. A single pin supplied alongside a set must belong to that set.
- Runs all 43 generated shared public-config cases and 39 pinned-envelope expectations, including a trusted set member other than the single pin and an untrusted signer. Set-only configurations remain supported.
- Keeps accepted configurations within the JSON bound when serialized again. Near the limit, `toJson()` omits redundant derived IDs and the duplicate first pin while preserving every ordered trusted pin and any explicit non-first single pin.

No tag, publication, emulator or device acceptance is claimed for this patch. See VERIFICATION.md for local gate evidence.

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
