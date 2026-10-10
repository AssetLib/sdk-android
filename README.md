# Assetlib Android SDK

Native Kotlin client for signed Assetlib image releases. Android 8.0/API 26+, Kotlin coroutines, ordinary Android `Bitmap` and Compose `Image`. Version **0.3.1-preview.1** standardizes bounded public configuration and signing-key sets across the SDKs. It includes staging configurations, appearance and arm variant cells, an app-supplied arm decision callback, localized artwork descriptions, and demand-sized PNG/WebP renditions. It remains a developer preview, not a production support commitment.

[Console](https://console.assetlib.dev) · [Native travel demo](https://github.com/AssetLib/demo-android) · [JavaScript SDK](https://github.com/AssetLib/sdk-js)

The app owns its screens and bundled fallbacks. Assetlib changes artwork assigned to compatible, declared placements. This SDK does not change layouts or executable code, discover unused assets, or ship Figma/experimentation integrations.

## Install the preview

Download `assetlib-android-0.3.1-preview.1.aar` from the [exact release](https://github.com/AssetLib/sdk-android/releases/tag/v0.3.1-preview.1), verify its SHA-256 against `SHA256SUMS`, and place it in your app's `libs/`. The demo contains a repeatable, hash-locked downloader. This AAR does not bundle dependencies; add these exact dependencies to your app:

```kotlin
implementation(files("libs/assetlib-android-0.3.1-preview.1.aar"))
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
implementation("org.bouncycastle:bcprov-jdk18on:1.86")
implementation("com.squareup.okhttp3:okhttp:4.12.0")
```

No Maven Central publication is claimed. R8 can shrink the app's dependency set; the unshrunk Bouncy Castle dependency is substantial. See `THIRD-PARTY-NOTICES.md`.

## Connect and render

Create a workspace in the console and download its **public SDK configuration**. Keep the pinned public key independently in this configuration; do not derive trust from a downloaded manifest. Never put admin credentials in an app.

Supply a single `pinnedPublicKey` or `pinnedPublicKeys` containing 1–16 distinct exact-PEM pins. Each must be Ed25519 SPKI PEM, at most 256 UTF-8 bytes. If both appear, the single pin must belong to the set. Optional `keyId` requires that explicit single pin; optional `keyIds` must match derived IDs in length and order. IDs remain SHA-256 of exact PEM, truncated to 16 hexadecimal characters. Known fields reject explicit nulls. Unknown fields are ignored, but their bytes count toward the 4096-byte UTF-8 JSON limit. For a set-only configuration, the non-optional `pinnedPublicKey` and `keyId` properties expose the first pin and its derived ID. Stored and downloaded releases must verify against this current set, so retain old trusted keys while retained releases still use them. Expanding or reordering the set preserves the storage namespace.

```kotlin
val config = PublicConfig.parse(publicConfigJson)
val client = AndroidAssets.client(context.applicationContext, config)
client.initialize() // Revalidate durable state and signatures, without a request.
client.refresh()    // Explicitly check the current signed release.
val asset = client.resolve(AppAssets.Travel.coast, targetPixels = AssetPixelSize(600, 450))
val bitmap = AndroidAssets.bitmap(asset)
// In Compose: bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Coast") }
// Otherwise render your normal bundled R.drawable fallback.
```

Suspend operations perform disk/network/decode work on `Dispatchers.IO`. Collect `client.status` for release state. A resolved image's `source` and `sequence` describe the actual artwork: earlier cached artwork can have an older sequence than the accepted manifest. `BUNDLE` intentionally has no downloaded bytes; the host app supplies its own image.

### Accessible artwork

Signed placements may include optional `accessibility: { "defaultLocale": "en", "descriptions": { "en": "An illustrated coastal escape", "th": "ภาพวาดชายฝั่ง" } }`. This is content metadata for that artwork version. It requires no additional manifest version. Older manifests can omit it. The client rejects malformed or null metadata, duplicate locales ignoring case, invalid locale tags, missing default descriptions, more than 32 locales, blank descriptions, and descriptions longer than 1,000 UTF-16 code units. Locale tags use letters followed by optional alphanumeric subtags separated by hyphens, with a maximum length of 63 characters.

`asset.localizedDescription("en-US")` tries an exact locale ignoring case, then progressively removes subtags, then uses the declared default. It returns `null` when the resolved artwork has no metadata. Pass an explicit language tag from your app; the helper does not read device settings. Descriptions remain paired with the actual resolved release through download, cache, restart, and fallback to a retained release. Missing remote metadata never borrows a description from the bundled image.

```kotlin
// Informative artwork: use its resolved description or an app-owned contextual label.
Image(bitmap.asImageBitmap(), contentDescription = asset.localizedDescription(languageTag))
// Decorative artwork: the app explicitly chooses contentDescription = null.
// Buttons keep an app-owned action label, such as "Explore coastal trips".
```

Keep the bitmap and its description in the same UI state update. Use a fixed app label only when remote replacements preserve that meaning. For informative images with no description, provide one at the call site. This SDK leaves Compose semantics and normal Android `Bitmap` rendering under app control; it does not automatically label images, infer decoration, or generate descriptions.

For bundled fallback artwork, add optional `bundledAccessibility` to the checked-in catalog using the same object shape. Code generation passes it to `AssetRef(..., bundledAccessibility = AssetAccessibility(...))`. A `BUNDLE` result exposes only that bundled metadata, never the latest downloaded artwork's description. If you choose a bundled image after a later render failure, read its description from `ref.bundledAccessibility` as well.

### Rendition selection and Compose

Pass the actual target **pixel** width and height, including display density. Without `targetPixels`, the SDK uses the generated reference's logical dimensions. Both target dimensions must be integers in 1–8192. Layout modifiers alone do not tell the SDK a target; the demo forwards Compose `onSizeChanged` measurements explicitly. A 300 dp image on a 2× display requests 600 pixels.

The signed extension uses `renditionSchemaVersion: 1`. An absent extension preserves legacy behavior; unknown versions, explicit nulls, malformed arrays, unsafe URLs, duplicate hashes, excessive bytes/pixels, and incompatible aspect ratios are rejected. Native selection supports WebP and PNG. Known SVG metadata is validated, but SVG is never downloaded or rendered by this client. Vector sources require server-prepared raster fallbacks on Android.

The smallest raster meeting both target dimensions is preferred. Ties use byte length, then hash. If no raster meets the target, larger rasters are tried first. Unavailable candidates fall through in this deterministic order, with legacy WebP as the final compatibility candidate. Historical releases use cached candidates only. Set `supportedFormats = listOf("image/webp")` to exclude PNG; a format list must be unique, nonempty, and include WebP. SVG is not a supported native format option.

Returned `mime`, `pixelWidth`, `pixelHeight`, `sha256`, `assetId`, and `sequence` describe the selected decoded raster. Every rendition is fully decoded with bounded dimensions, and its actual MIME and exact pixel dimensions must match its signed metadata before caching or use. Legacy WebP retains its aspect-compatible decoding rule. Source upload format is not inferred from delivered MIME.

The SDK has no Compose dependency. The demo's small `rememberAssetArtworkPainter` adapter returns a standard `BitmapPainter` for a verified raster, or `painterResource` for the bundled fallback. It is a raster adapter, not native SVG support. Android documents these [Painter types](https://developer.android.com/develop/ui/compose/graphics/images/custompainter) and [bounded bitmap metadata decoding](https://developer.android.com/reference/android/graphics/BitmapFactory.Options).

### Appearance, arms, and staging

`PublicConfig.environment` accepts `production` and `staging`. Use a public configuration whose manifest URL is `/api/delivery/{orgId}/{appId}/environments/{environment}/manifest`; the legacy `/api/delivery/{orgId}/{appId}/manifest` path remains valid only for production. The signed environment must match exactly. Durable state and cache are scoped to the manifest origin, organization, app, and environment. Production URL aliases and pinned key changes share this namespace; staging remains separate.

Pass `appearance = AssetAppearance.LIGHT` or `DARK`, and optionally `arm = "b"`, to `client.resolve`. Resolution tries `(arm, appearance)`, `(arm, any)`, `(control, appearance)`, then the slot's legacy `(control, any)` artwork. Without an arm, only control cells are considered; without appearance, only `any` artwork is considered. It never borrows another arm's cell. `appearance` and `arm` on the result describe the selected cell, with `null` meaning any/control; a bundled result has both null. Cache and retained-release fallback use the selected image's signed hash and metadata.

For app-owned assignment, pass a suspend callback through `AndroidAssets.client` (or the `AssetClient` constructor):

```kotlin
val client = AndroidAssets.client(context.applicationContext, config,
    decide = { key, arms -> experimentAssignments[key]?.takeIf { it in arms } })
```

The callback runs once per resolution when the latest compatible slot declares arms and the request omits `arm`. Any explicit string, including `"control"`, bypasses it. An undeclared explicit arm selects control artwork and keeps `armSource = EXPLICIT`. The callback runs outside the client mutex with a default 1,500 ms wait; set `decisionTimeoutMillis` on `AndroidAssets.client` or `AssetClient` to configure 100–10,000 ms. A null, undeclared, throwing, or timed-out callback result selects control with `INVALID_DECISION` and a reason in `message`. Cancellation of the resolving coroutine still propagates. The callback job is cancelled on timeout, but app code that ignores cancellation may continue running; its late result is ignored. After the callback, the client revalidates durable state and selects against the current accepted release, including its current arm declarations. Failed revalidation returns bundled artwork. Explicit requests, refresh, and initialization do not wait for a pending callback. Valid decisions use `DECISION`; no callback or no declared arms uses `CONTROL`. Log actual exposure through your app's experiment tool when artwork is displayed, not from the decision callback.

In your app's Compose code, feed the theme and measured pixel demand into resolution and keep the bitmap with its resolved description. The demo-local painter adapter still accepts a bitmap and bundled resource:

```kotlin
// Inside your composable; arm is a nullable app-owned override.
val appearance = if (isSystemInDarkTheme()) AssetAppearance.DARK else AssetAppearance.LIGHT
val artwork by produceState<Pair<ResolvedAsset, Bitmap?>?>(
    null, client, ref, targetPixels, appearance, arm
) {
    value = null
    val asset = client.resolve(ref, targetPixels, appearance = appearance, arm = arm)
    value = asset to AndroidAssets.bitmap(asset)
}
val bitmap = artwork?.second
val description = if (bitmap != null) artwork?.first?.localizedDescription(languageTag)
    else ref.bundledAccessibility?.localizedDescription(languageTag)
Image(
    painter = rememberAssetArtworkPainter(bitmap, R.drawable.coast),
    contentDescription = description // Supply an app label if informative artwork has no metadata.
)
```

`AndroidAssets.bitmap(client, ref, targetPixels, appearance, arm)` also passes these parameters through when only a bitmap is needed. Compose stays in the app; the SDK adds no Compose dependency. Manifests use `variantSchemaVersion: 1` with declared, unique appearance/arm values and validated cell image descriptors. Declared variants without cells are valid. Native clients ignore slot and cell `states`.

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

### Tintable icons

Unreleased: on `main`, not in 0.3.1-preview.1, which ignores `rendering`.

A catalog placement can declare `"rendering": "template"`. Its published artwork is a single-color alpha mask, and the app supplies the color at render time from its theme, selected state, or dark mode. `rendering` may be `"original"` (the default) or `"template"`; any other value fails generation. A template placement generates `AssetRef("tab.trips", 24, 24, rendering = AssetRendering.Template)`. To change a placement's rendering, declare a new key.

```json
{"key":"tab.trips","symbol":["Tabs","trips"],"width":24,"height":24,"rendering":"template"}
```

A reference uses only artwork published with the same rendering, checked on the selected appearance or arm cell. A different or unknown rendering is treated like incompatible dimensions: that release's artwork is never read from the cache or downloaded, earlier releases are checked the same way, and otherwise the result is `BUNDLE`. Other placements are unaffected. The SDK still returns a plain `Bitmap` and adds no Compose dependency; the app tints it. Bundle a single-color fallback as well, such as a VectorDrawable.

```kotlin
val side = with(LocalDensity.current) { 24.dp.roundToPx() } // logical size × density
val bitmap by produceState<Bitmap?>(null, client, side) {
    value = AndroidAssets.bitmap(client, AppAssets.Tabs.trips, AssetPixelSize(side, side))
}
Icon(
    // The demo's adapter: BitmapPainter for the verified mask, else painterResource for the VectorDrawable fallback.
    painter = rememberAssetArtworkPainter(bitmap, R.drawable.ic_tab_trips),
    contentDescription = null, // The surrounding control owns the label.
    tint = LocalContentColor.current,
    modifier = Modifier.size(24.dp),
)
```

`Icon` applies the same tint to the remote mask and to the VectorDrawable that `painterResource` loads, so switching between them keeps the color. Request `targetPixels` as logical size × density: without it, resolution asks for the logical size and selects a 1× mask on a denser screen.

## Verification and fallback

- Exact UTF-8 signed payload bytes; pinned Ed25519 SPKI public key; `keyId` is the first 16 lowercase hex characters of SHA-256 of the exact PEM bytes.
- HTTPS, exact organization/app delivery paths, no URL credentials/query/fragment, same-origin asset URLs, and no HTTP redirects. Default call/connect/read timeout 8 seconds (configurable 20 ms–30 seconds).
- Schema, environment, release sequence, slot/rendition types and limits, asset byte count and SHA-256 are checked before use. Native image validation checks PNG/WebP decode, at most 8,192 per edge, at most 16,777,216 pixels, and within 2% relative aspect-ratio tolerance. Logical placement dimensions need not equal delivered pixel dimensions; declared rendition pixel dimensions must match exactly.
- The default storage locks and rechecks state across clients/processes before an atomic replacement. Lower sequences and different payload bytes reusing a sequence are rejected, including reordered/normalized equivalent JSON. Rollback is a **new higher sequence** publishing previous artwork.
- Resolve uses the latest compatible cached image, then downloads that release's image, then tries earlier verified cached releases, then the host's bundle. It does not download historical fallback releases.
- Durable state lives under `noBackupFilesDir`; disposable artwork uses `cacheDir`. Each connection namespace retains at most 8 signed releases / 3 MiB state and 50 MiB / 100 cache entries. The OS may evict cached images; retain bundled fallbacks. Changing pinned keys or switching between production manifest URL aliases preserves replay protection and cached artwork. On first use, verified state from the previous full-URL/single-key namespace is migrated atomically; unverified legacy state is left untouched and ignored. A durable migration marker prevents importing legacy state again.
- Invalid durable state fails closed to the bundle. Disconnecting does not clear replay protection. Clearing app data/reinstalling resets local trust history; no client-only scheme can stop rollback of all local app state by a privileged attacker.

The public `AssetClient`/`AssetStorage` interfaces also permit custom integrations. Custom storage must provide the documented atomic comparison, durability, locking, and bounds. The default client and Android factory both perform native image validation. Version 0.2 replaces the former optional Boolean validator with `decodeImage: (ByteArray) -> AssetImageInfo?`; custom decoders must fully validate supported raster bytes and return actual MIME/dimensions. JVM tests inject a fixture metadata reader; Android instrumentation separately tests the real decoder. Preview networking is explicit refresh plus on-demand resolution, not a background download service or analytics SDK.

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

The checked-in synthetic interop corpus covers 43 public-config cases with 39 signature verification expectations, 115 signed manifest cases, 18 variant resolution cases and 10 rendering resolution cases, including staging, UTF-8, key/signature tampering, invalid schema/URLs/renditions/accessibility/variant/rendering metadata, and stateful replay/equivocation. JVM tests load each case's configuration and every resolution entry. Unit tests additionally cover decision callbacks, cell selection through cache/restart/historical fallback, rendering matching through cache and retained releases without downloads, staging isolation, target selection, legacy fallback, locale lookup, description pairing across remote/cache/bundle states, concurrent writers, cache corruption and limits. Instrumented tests decode actual PNG/WebP data, reject incorrect signed dimensions/MIME and invalid PNG data, and exercise an independent offline client. They do not establish performance on all Android devices. The additional Android regression fixtures can be regenerated with `node scripts/generate-native-rendition-tests.mjs`; their signing seed is public test data and must never be used in a service.

Only public artwork should be published to the hosted preview: delivery URLs are publicly retrievable. It is bounded preview infrastructure. See `SECURITY.md` for trust limits.
