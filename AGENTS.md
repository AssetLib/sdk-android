# AGENTS.md

Assetlib Android SDK: the public, MIT-licensed Kotlin library (Gradle module `:sdk`, package `com.assetlib.sdk`) that verifies signed artwork releases, keeps a verified local cache, and returns ordinary Android `Bitmap` values. The app keeps its own bundled fallbacks and Compose code; the SDK has no Compose dependency. API 26+, compileSdk 36, JDK 17. It ships as an AAR attached to GitHub prereleases with a `SHA256SUMS` file; no Maven publication. Manifests are signed by the hosted console (https://console.assetlib.dev). Developer preview; latest release `v0.3.1-preview.1` (October 9, 2026). The Swift SDK (AssetLib/sdk-swift) and the JavaScript SDK (AssetLib/sdk-js) implement the same contract; AssetLib/demo-android consumes the released AAR.

## Setup and commands

Needs JDK 17, Android SDK platform 36 and build-tools 36.1.0, and Node 22+ for the scripts. Point Gradle at the SDK with `ANDROID_HOME` or a local `local.properties` (`sdk.dir=...`). `local.properties` is machine-specific and gitignored: never commit it. The Gradle 8.14.3 wrapper pins its distribution checksum.

CI (`.github/workflows/check.yml`, `ubuntu-latest`, on push, pull request, and manual dispatch) runs these. All must pass before work is called done:

```sh
node --test scripts/codegen.test.mjs
./gradlew :sdk:testDebugUnitTest :sdk:lint :sdk:assembleRelease
node scripts/prepare-release.mjs   # copies the release AAR to build/release/ and writes SHA256SUMS
```

- Instrumented tests, not run in CI: `./gradlew :sdk:connectedDebugAndroidTest`. Needs a running API 26+ emulator or a connected device. `AndroidAssetsTest` uses the real `BitmapFactory` decoder; its assets come from `src/test/resources` through the `androidTest` source set.
- Optional read-only hosted check: `ASSETLIB_PUBLIC_CONFIG_FILE=/absolute/path/public-config.json ./gradlew :sdk:testDebugUnitTest --rerun-tasks`. The Gradle test task forwards the variable; when it is blank the test is skipped. Keep the file outside the repo; never commit or print it.
- Android-only regression fixtures in `sdk/src/test/resources/rendition-native/`: `node scripts/generate-native-rendition-tests.mjs`.
- Offline codegen that apps run: `node scripts/generate-assets.mjs catalog.json AppAssets.kt com.example.app [--check]`.
- Gradle must write to the user's Gradle home. In a sandbox that blocks it, the wrapper fails before compiling (see VERIFICATION.md). Report that; a hand-run compiler or JUnit pass is not evidence that the Gradle build or lint passed.

## Layout

- `sdk/src/main/java/com/assetlib/sdk/AssetClient.kt`: `initialize`, `refresh`, `resolve` under one `Mutex`; decision callback; `ResolvedAsset`, `ClientStatus`.
- `Protocol.kt`: `PublicConfig` parsing, `Limits`, Ed25519 via Bouncy Castle `rfc8032` (no JCA provider), manifest, rendition, and variant validation, storage `namespace`.
- `Storage.kt`: `FileAssetStorage` (process and file locks, atomic replace, one-time namespace migration, bounded cache).
- `Transport.kt`: OkHttp with redirects off, no cookies, call timeout (default 8,000 ms).
- `AndroidAssets.kt`: factory (state in `noBackupFilesDir`, cache in `cacheDir`) and `BitmapFactory` decoding.
- `sdk/src/test/java/...`: JVM unit tests. `FixtureDecoder.kt` replaces `BitmapFactory` with a fixture metadata reader, so JVM tests do not exercise the Android decoder.
- `sdk/src/test/resources/fixtures/`: shared contract corpus (next section).
- `THIRD-PARTY-NOTICES.md`, `licenses/`: runtime dependency notices.

## Shared contract corpus

- `sdk/src/test/resources/fixtures/` is a byte-for-byte copy of the signed interoperability corpus that the Swift SDK vendors at `Tests/AssetLibTests/Fixtures/` and that is also run against the JavaScript SDK where the corpus is generated: 115 signed manifest cases (`cases.json` is the index, each case names its `production` or `staging` config), 18 variant resolution entries, 6 stateful cases, 2 byte-failure cases, 4 rendition selections, and 10 rendering resolution cases (`rendering.json`).
- The corpus is generated outside this repo. Never hand-edit, re-sign, or reformat a fixture: signatures cover exact payload bytes, and outer JSON whitespace differs from the signed payload on purpose. A contract change lands in the corpus first; then replace the whole directory here and in sdk-swift in the same pass.
- No CI step checks the copy against its source. A `diff -r` against the Swift repo's fixture directory should show only its local `README.txt`.
- `keys/TEST_ONLY_*` is deliberately public test material. Never trust it outside tests.
- Behavior must match the Swift and JavaScript SDKs. A semantic change here needs the matching change and corpus cases in the other SDKs.

## Invariants

- **Bundled fallback always wins on failure.** Invalid config, storage, signature, scope, download, hash, decode, dimensions, or decision state returns `AssetSource.BUNDLE` with no bytes, and the app renders its own drawable. Never return unverified bytes. Never remove or weaken a fallback path. Caller cancellation propagates.
- **Signatures.** Verify Ed25519 over the exact UTF-8 bytes of the `payload` string before reading it. Trust comes only from `pinnedPublicKey` / `pinnedPublicKeys` in the public config; an envelope cannot supply a key. The key ID is the first 16 hex characters of SHA-256 of the exact PEM text. Payload `orgId`, `appId`, and `environment` must equal the config exactly.
- **Delivery.** HTTPS, same origin, exact scoped paths: `/api/delivery/{orgId}/{appId}/environments/{environment}/manifest`, plus the legacy `/api/delivery/{orgId}/{appId}/manifest` for production only. No redirects or cookies. Byte limits are enforced while reading.
- **Replay and rollback.** Sequences never decrease. Equal sequence with a different payload string is rejected, including reformatted or Unicode-normalized JSON. Rollback is a new, higher sequence.
- **Durable state.** Persist a verified higher release before exposing it. State is re-synced under the mutex for every operation. Unverifiable state fails closed to the bundle; never delete it automatically. Clearing app data is the only reset.
- **Storage namespace** is SHA-256 of origin, org, app, and environment (`PublicConfig.namespace`). Staging and production stay isolated. Changing the formula strands replay floors and caches; it requires a verified one-time migration like `legacyNamespaces` plus the `legacy-migration.complete` marker, with `NamespaceTest` extended.
- **Cache and offline restart.** Cached bytes are re-verified on read (length, SHA-256, decoded MIME and dimensions). Only the latest accepted release may download; older retained releases are cache-only fallbacks.
- **Renditions.** Smallest raster meeting the target, else largest; ties by byte length, then hash; the legacy WebP slot is always the last candidate. Format lists must be unique and include `image/webp`. SVG metadata is validated, never fetched.
- **Variants.** Resolution order: (arm, appearance), (arm, any), (control, appearance), legacy slot. Never borrow another arm's artwork. Native clients ignore `states`.
- **Rendering.** The selected descriptor's `rendering` (absent = original) must equal `AssetRef.rendering`. A mismatch or well-formed unknown value is incompatible like wrong dimensions: no cache read, no download, for retained releases too. Malformed values reject the manifest. `RenderingTest` covers this.
- **Decision callback.** Never invoke it while holding the mutex. It runs only when no explicit `arm` is passed and the latest matching slot declares arms. Default wait 1,500 ms, allowed 100..10,000. Timeout, throw, null, or an undeclared arm resolves control with `INVALID_DECISION` and a reason in `message`. The job is cancelled, never joined. Afterwards the client re-syncs state and re-checks the arm against the current release. `DecisionRaceTest` and `VariantTest` cover this.
- **Limits** in `Limits` and the README "Verification and fallback" section are contract values. Change them only together with the corpus.
- No background downloads, analytics, user identifiers, or exposure logging in the SDK. `consumer-rules.pro` relies on there being no reflective serialization (JSON tree parsing only) and no crypto provider registration; keep it that way or add the keep rules. Lint runs with `abortOnError = true`.

## Public API and compatibility

- Public surface: `AssetClient`, `AndroidAssets`, `PublicConfig`, `AssetRef`, `AssetRendering`, `AssetPixelSize`, `AssetImageInfo`, `AssetAppearance`, `AssetArmSource`, `AssetSource`, `AssetAccessibility`, `ResolvedAsset`, `ClientStatus`, `RefreshResult`, `Limits`, `sha256`, the `AssetStorage` and `AssetTransport` interfaces, `FileAssetStorage`, `HttpsTransport`.
- Secondary constructors on `AssetClient`, the second `AndroidAssets.client` overload, and the three-argument `resolve` overload exist to keep older call shapes compiling. Keep them. Add new parameters with defaults, after existing ones.
- Adding a property to a public data class changes its JVM constructor signature even when Kotlin call sites still compile. Say so in RELEASE-NOTES.md ("recompile consuming applications").
- Apps commit generated `AppAssets.kt` that calls `AssetRef(key, width, height, bundledAccessibility = AssetAccessibility(locale, mapOf(...)), rendering = AssetRendering.Template)`, with each named argument only when the catalog declares it. Keep it source-compatible.
- The AAR does not bundle dependencies, and apps add the exact versions listed in the README. A dependency change must update `sdk/build.gradle.kts`, the README install block, and `THIRD-PARTY-NOTICES.md` (plus `licenses/` when a license text changes) together.

## Release and versioning

- Gradle declares no version. Strings to change together: `name` in `scripts/prepare-release.mjs`, README (the version on line 3, the release link, the `implementation(files(...))` line), and a new RELEASE-NOTES.md heading. The CI artifact name is deliberately unversioned.
- Steps the repo supports: `./gradlew :sdk:assembleRelease`, then `node scripts/prepare-release.mjs` to produce `build/release/assetlib-android-<version>.aar` and `SHA256SUMS`. Tags are `v<version>`, annotated, with a GitHub prerelease carrying both files and the hash in its notes. CI on the tag uploads a Linux-built AAR; comparing its SHA-256 with the published file is how reproducibility was checked for `0.1.0-preview.1`.
- Never move or delete a published tag or replace a published AAR; apps verify the hash.
- `v0.2.1-preview.1` was tagged from a side branch and is not an ancestor of `main`. `phase-4-native-parity` is fully contained in `main`. Work from `main`.
- Do not tag, push, or publish unless the maintainer asks.

## Verified vs not verified

- Established for `0.3.0-preview.1` and `0.3.1-preview.1`: the JVM unit tests (one optional hosted test skipped), lint, and the release build against the full corpus, plus green CI on `main` and on the tag.
- Instrumented runs are recorded for `0.1.0-preview.1`, `0.2.0-preview.1`, `0.2.1-preview.1`, and (on October 9, 2026) the `0.3.0-preview.1` source and the `v0.3.1-preview.1` release, all on Android 36 emulators. `AccessibilitySemanticsTest` was restored to `main` on October 9, 2026 after being dropped when 0.3.0 was cut. No emulator, device, or hosted run exercises the `0.3.0` staging, variant, or decision paths in a real app. Physical devices, API 26 devices, manual TalkBack, and performance have never been established.
- Do not claim emulator, device, or hosted acceptance you did not run. When you do run a check, add a dated VERIFICATION.md entry with the exact command and output, and no local absolute paths or configuration contents.

## Don'ts

- Don't weaken verification, limits, or fallbacks to make a test pass, and don't edit fixtures to fit the code.
- Don't commit `local.properties`, `build/`, `.gradle/`, `.idea/`, public configurations, real keys, or hosted URLs. Fixtures use `.example` hosts only.
- Don't add a Compose or other UI dependency to `:sdk`.
- Don't make placements console-first; they come from the app's checked-in catalog.
- When you find a confirmed defect, fix it, test it, and note it in RELEASE-NOTES.md in the same change. If a fix is unsafe now, say why and when it will happen.
- In prose the product is "Assetlib". `AssetLib` is only the GitHub org name. Label planned features as planned.
