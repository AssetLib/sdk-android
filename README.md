# Assetlib Android SDK

Native Kotlin client for signed Assetlib image releases. Android 8.0/API 26+, Kotlin coroutines, ordinary Android `Bitmap` and Compose `Image`. Version **0.1.0-preview.1** is a developer preview, not a production support commitment.

[Console](https://assetlib-console.vercel.app) · [Native travel demo](https://github.com/AssetLib/demo-android) · [JavaScript SDK](https://github.com/AssetLib/sdk-js)

The app owns its screens and bundled fallbacks. Assetlib changes artwork assigned to compatible, declared placements. This SDK does not change layouts or executable code, discover unused assets, or ship Figma/experimentation integrations.

## Install the preview

Download `assetlib-android-0.1.0-preview.1.aar` from the [exact release](https://github.com/AssetLib/sdk-android/releases/tag/v0.1.0-preview.1), verify its SHA-256 against `SHA256SUMS`, and place it in your app's `libs/`. The demo contains a repeatable, hash-locked downloader. This AAR does not bundle dependencies; add these exact dependencies to your app:

```kotlin
implementation(files("libs/assetlib-android-0.1.0-preview.1.aar"))
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
implementation("org.bouncycastle:bcprov-jdk18on:1.86")
implementation("com.squareup.okhttp3:okhttp:4.12.0")
```

No Maven Central publication is claimed. R8 can shrink the app's dependency set; the unshrunk Bouncy Castle dependency is substantial. See `THIRD-PARTY-NOTICES.md`.

## Connect and render

Create a workspace in the console and download its **public SDK configuration**. Keep the pinned public key independently in this configuration; do not derive trust from a downloaded manifest. Never put admin credentials in an app.

```kotlin
val config = PublicConfig.parse(publicConfigJson)
val client = AndroidAssets.client(context.applicationContext, config)
client.initialize() // Revalidate durable state and signatures, without a request.
client.refresh()    // Explicitly check the current signed release.
val asset = client.resolve(AppAssets.Travel.coast)
val bitmap = AndroidAssets.bitmap(asset)
// In Compose: bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Coast") }
// Otherwise render your normal bundled R.drawable fallback.
```

Suspend operations perform disk/network/decode work on `Dispatchers.IO`. Collect `client.status` for release state. A resolved image's `source` and `sequence` describe the actual artwork: earlier cached artwork can have an older sequence than the accepted manifest. `BUNDLE` intentionally has no downloaded bytes; the host app supplies its own image.

### Typed references, generated offline

Commit a catalog with stable placement keys, logical dimensions, and Kotlin symbols. For example:

```json
{"schemaVersion":1,"placements":[{"key":"travel.coast","symbol":["Travel","coast"],"width":1200,"height":900}]}
```

```sh
node scripts/generate-assets.mjs catalog.json app/src/main/java/com/example/app/AppAssets.kt com.example.app
# CI check; no network and no credentials:
node scripts/generate-assets.mjs catalog.json app/src/main/java/com/example/app/AppAssets.kt com.example.app --check
```

Use `AppAssets.Travel.coast` at image call sites. Generation validates duplicate keys/symbols, Kotlin identifiers, and dimensions. It is a checked-in project catalog, not automatic discovery of every image or observed runtime usage. Changing a Kotlin symbol need not change its remote key. Keep old placements while installed app versions still need them; incompatible dimensions fall back instead of silently changing a contract.

## Verification and fallback

- Exact UTF-8 signed payload bytes; pinned Ed25519 SPKI public key; `keyId` is the first 16 lowercase hex characters of SHA-256 of the exact PEM bytes.
- HTTPS, exact organization/app delivery paths, no URL credentials/query/fragment, same-origin asset URLs, and no HTTP redirects. Default call/connect/read timeout 8 seconds (configurable 20 ms–30 seconds).
- Schema, environment, release sequence, slot types/limits, asset byte count and SHA-256 are checked before use. Native image validation checks WebP decode, at most 8,192 per edge, at most 16,777,216 pixels, and within 2% relative aspect-ratio tolerance. Logical placement dimensions need not equal delivered pixel dimensions.
- The default storage locks and rechecks state across clients/processes before an atomic replacement. Lower sequences and different payload bytes reusing a sequence are rejected, including reordered/normalized equivalent JSON. Rollback is a **new higher sequence** publishing previous artwork.
- Resolve uses the latest compatible cached image, then downloads that release's image, then tries earlier verified cached releases, then the host's bundle. It does not download historical fallback releases.
- Durable state lives under `noBackupFilesDir`; disposable artwork uses `cacheDir`. Each connection namespace retains at most 8 signed releases / 3 MiB state and 50 MiB / 100 cache entries. The OS may evict cached images; retain bundled fallbacks. Switching configurations creates separate namespaces.
- Invalid durable state fails closed to the bundle. Disconnecting does not clear replay protection. Clearing app data/reinstalling resets local trust history; no client-only scheme can stop rollback of all local app state by a privileged attacker.

The public `AssetClient`/`AssetStorage` interfaces also permit custom integrations. Custom storage must provide the documented atomic comparison, durability, locking, and bounds; the standard Android factory supplies native image validation. Preview networking is explicit refresh plus on-demand resolution, not a background download service or analytics SDK.

## Build and test

Install JDK 17, Android SDK 36 and build-tools 36.1.0; point `ANDROID_HOME` at the SDK. Gradle 8.14.3 wrapper includes its official distribution checksum. Node 22+ is used only for offline code generation.

```sh
./gradlew :sdk:testDebugUnitTest :sdk:lint :sdk:assembleRelease
node --test scripts/codegen.test.mjs
# With an Android API 26+ emulator/device:
./gradlew :sdk:connectedDebugAndroidTest
# Optional read-only real-service smoke test; JSON contains public configuration only:
ASSETLIB_PUBLIC_CONFIG_FILE=/absolute/path/public-config.json ./gradlew :sdk:testDebugUnitTest --rerun-tasks
```

The checked-in synthetic interop corpus covers 43 signed cases, including UTF-8, key/signature tampering, invalid schema/URLs, and stateful replay/equivocation. Unit tests additionally cover concurrent writers, restart/offline fallback, cache corruption and limits. Instrumented tests decode actual WebP data and exercise an independent offline client. They do not establish performance on all Android devices.

Only public artwork should be published to the hosted preview: delivery URLs are publicly retrievable. It is bounded preview infrastructure. See `SECURITY.md` for trust limits.
