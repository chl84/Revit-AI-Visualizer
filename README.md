# Vizruna

Formerly Revit AI Visualizer. The Android app identity and existing credential storage remain compatible with upgrades; the Windows executable is now `Vizruna.exe`.

Native Android and Windows apps for turning a captured or imported Autodesk Revit viewport into a photorealistic architectural visualization. Android uses Kotlin, Jetpack Compose and CameraX; Windows uses C# and WPF on .NET 10. Both connect directly to OpenAI without an application backend or database. GitHub Actions builds and tests the Windows app.

Architectural fidelity is the priority. The fixed prompt instructs the model to retain visible geometry, openings, proportions, rooflines, and the camera viewpoint. **Generative image editing cannot guarantee geometric accuracy.** Always inspect the original and overlay before using a result.

## Windows

Windows development is paused. The optional Advanced environment controls below are currently Android-only.

See [Windows download, usage and build instructions](windows/README.md). The **Vizruna Windows** GitHub Actions workflow produces a self-contained portable x64 executable, downloadable from a successful run. No API key is needed for CI.

## Android build and run

Requirements: JDK 17 or 21, Android SDK Platform 36, Build Tools 35.0.0, and an Android 10 (API 29) or newer device. Android Studio can install the SDK components and run the project. Use the bundled Gradle wrapper; dependencies are pinned to stable versions.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
```

With the intended emulator or device connected, run the device checks:

```sh
./gradlew :app:connectedDebugAndroidTest
```

If several devices are connected, set `ANDROID_SERIAL` to the intended device serial. Device tests use generated fixtures and never confirm a paid image request.

Select the `app` run configuration in Android Studio, or install the debug APK on a connected device:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Set `ANDROID_HOME` to your SDK location or let Android Studio create an ignored `local.properties`. Never commit a machine-specific SDK path. The first build needs network access for dependencies. No API key is needed to build or run local tests.

## Workflow

1. **Capture:** enable camera access and photograph the Revit viewport, or use the system photo picker. Confirm the preview with **Prepare image**. Import also works without a camera or camera permission.
2. **Prepare:** drag the corner handles to crop. Enable **Correct perspective** for independent corner placement, in top-left, top-right, bottom-right, bottom-left order. For perspective correction, enter the actual viewport aspect ratio, such as `16:9`; leaving it blank estimates the ratio from the photographed edges and can affect proportions. Use the keyboard's **Done** action to close it. Switching correction modes resets the selection. **Visualize** asks for a masked API key before the first request. Turn on **Remember on this device** to reuse it after closing the app; leave it off for a session-only key. On wide screens, the preview and scrollable controls appear side by side.
3. **Result:** switch between **Original**, **Result**, and **Overlay**. Adjust original opacity and pinch/drag to zoom and pan both views together. **Save Image** writes the exact returned PNG to `Pictures/Vizruna` through MediaStore.

In the comparison, **Original** means the prepared image actually sent to the model. The untouched capture/import remains separate. Images with different aspect ratios are centered and fitted inside the same viewport without stretching or cropping; the same zoom and pan apply to both. This is a visual check, not an automatic geometry assessment.

In landscape Capture layouts, the shutter and import controls sit beside the live preview. Captured-image review also places its actions beside the image, giving both previews the full available height. Narrower layouts keep the actions below the image. Camera previews preserve the full frame and its proportions; capture resolution remains the highest available setting.

## Optional Advanced environment (Android)

Advanced is off by default in Prepare. Leaving it off sends exactly the existing base prompt and one clean prepared image; it requires no place, date, weather or arrow input.

When enabled, weather and snow work without selecting a location or placing a north arrow. A location by itself guides environmental character. Date/time and solar orientation are used only when both a location and a confirmed north arrow are selected; otherwise the app sends one clean image with weather/snow instructions and skips the paid analysis. Use **Remove location** or **Remove north arrow** to return to that simpler flow.

Optional controls:

1. Enter any place name and press **Find place**. Confirm the intended result using its full name and WGS84 coordinates. Search combines Android device geocoding and [Open-Meteo / GeoNames](https://open-meteo.com/en/docs/geocoding-api); availability and coverage vary. **Use coordinates** supports a precise point anywhere, including natural regions, deserts and oceans, without depending on search. For a large region such as the Sahara, choose a specific point: it has no single solar position or civil time zone.
2. For solar orientation, choose a local date (1900–2100) and time at that place. The displayed IANA time zone uses Android's date-specific rules, including DST. The phone's zone and the lookup service's current UTC offset are not substituted. A nonexistent spring-transition time is rejected; an autumn repeated time requires selecting its UTC offset. Coordinates and the time zone can be adjusted explicitly. Keep the device's time-zone data current.
3. Choose **Clear**, **Partly cloudy**, **Overcast** or **Rain**. **Snow cover** independently requests settled snow on the image; rain can produce wet/slushy snow.
4. Press **Place north arrow**. This opens the actual cropped, perspective-corrected image. Drag the base to move the arrow; drag N to rotate/resize it, or use the rotation slider. Confirm the arrow to enable solar orientation when a location is selected. An absent or unconfirmed arrow never blocks weather/snow generation. Changing the crop, perspective mode or aspect ratio invalidates that confirmation. No camera heading or tilt is requested.

Version 0.1.7 preserves unconfirmed manual location fields across screen rotation and keeps known time-zone metadata when combining duplicate search results. Twilight prompts also include the computed below-horizon solar bearing to orient diffuse sky glow. See [validation status](VALIDATION.md).

The app computes topocentric solar azimuth and elevation locally using [solarpositioning 3.0.3](https://github.com/KlausBrunner/solarpositioning), an independent MIT-licensed implementation of the [NREL SPA algorithm](https://docs.nrel.gov/docs/fy08osti/34302.pdf). Calculations use exact selected coordinates, lookup elevation where available (otherwise sea level), estimated delta T, and UTC as an approximation to UT1. Apparent elevation assumes 1010 hPa and 10°C; the upper-limb visibility threshold is −0.833° geometric solar-centre elevation above a flat horizon. Civil, nautical and astronomical twilight and night are distinguished, including polar day/night. Terrain can obscure the Sun sooner; local atmospheric conditions also change refraction, especially near the horizon. These inputs do not measure terrain or weather.

North/South Pole names resolve offline to latitude ±90°, longitude 0° and explicit UTC. At the exact pole, compass north is undefined. The UI and model instructions identify a convention: N is the limiting local-north direction approached along the selected longitude. Changing longitude changes that reference meridian. This avoids presenting a pole's arrow or time zone as uniquely defined.

The clean full-resolution image remains the authoritative first input to image generation. When solar orientation is enabled, a second image is a sampled copy (at most 1600 px on its longest edge) with the same north arrow shown by the editor. This is an orientation guide; its marks must not appear in the output. The image model receives the computed solar bearing, altitude, opposing ground-shadow bearing, shadow-length relationship, and the scene analysis described below. Night/twilight suppress direct sunlight and solar cast shadows. Overcast and rain suppress the visible disc and hard solar shadows. Location also guides terrain, vegetation and environmental materials; it must not introduce landmarks or redesign the building. Only one output image is requested. Solar orientation adds a paid analysis request and reference-image/text input processing to the image request.

The public Open-Meteo service is used for this personal development build under its [noncommercial API terms](https://open-meteo.com/en/terms), with user-triggered lookups and a session search cache. Commercial deployment needs an appropriately licensed lookup service. Data attribution is available in Advanced; the solar library's MIT notice is included in the APK assets.

Solar calculations and the requested physical constraints are deterministic; the image model's interpretation and resulting shadows are generative and **cannot be guaranteed physically exact**. Inspect the result. Neither crop selection nor the clean comparison input is altered by the north guide. Advanced settings and working guide images are session-local.

## Advanced orientation analysis (Android 0.1.9)

When Advanced is on with both a selected location and confirmed north arrow, **GPT-5.4 mini** first analyzes a clean sampled PNG and the matching north-arrow guide through `POST https://api.openai.com/v1/responses`. Both analysis images are at most 1600 px on their longest edge; the later authoritative image input remains at full prepared resolution. The selected place/time, computed solar angles, weather and snow choice are supplied as context. The analysis does not recalculate the Sun, generate an image, or call tools. Advanced off, or Advanced without either required solar input, skips this request completely.

The analysis uses a strict JSON schema with `reasoning.effort=medium`, `max_output_tokens=12000`, `store=false` and `stream=false`. The output budget includes internal reasoning as well as the final JSON; version 0.1.8 raises it from 4000 to reduce incomplete responses caused by exhausting that budget. This is a maximum, not a fixed token charge. It identifies north in the inferred scene, the expected solar side and ground-shadow direction, up to eight named visible regions, estimated outward surface-normal azimuth/elevation, possible occlusion and uncertainty notes. Descriptors are bounded and quoted as scene data in the final prompt. There are no confidence percentages or quality scores. A normal is not a facade-edge bearing: a vertical wall's normal elevation is 0°, an upward horizontal surface's is 90°. Unknown normals remain unknown.

The app computes each region's direct-sun eligibility from the dot product of its inferred normal and the calculated solar direction, with overrides for twilight/night, overcast/rain and inferred occlusion. A north-facing vertical gable cannot receive direct south sunlight. The final image prompt includes those region-specific checks, tells the renderer to replace conflicting source illumination, and retains all geometry-preservation requirements. Surface orientations and occlusion are still AI estimates; the calculation does not establish that the inferred geometry or generated lighting is correct.

An unsuccessful, refused, incomplete, oversized, malformed or ambiguous analysis stops before any image-generation POST. The analysis has an independent five-minute deadline (its non-streamed answer arrives only after all reasoning and output tokens), a 256 KiB response limit, a 12 MiB per-input-image limit, and no automatic retries or redirects. Cancellation closes its HTTP call; cancellation after analysis also prevents the image POST. If an analysis was already submitted, it may still be billed. Starting again runs a new analysis and may incur another charge. The OpenAI project needs access to both GPT-5.4 mini and the existing image model; no silent model fallback is performed.

Successful results offer **Review lighting analysis**, showing the selected time/place, calculated solar angles, inferred north and face directions, local lighting checks and uncertainty notes. This session-local report is not embedded in the saved PNG. The Advanced controls and credential dialog explain the extra paid analysis and waiting time.

Incomplete responses now distinguish output-budget exhaustion (`ANALYSIS_OUTPUT_LIMIT`), content filtering and other non-completed statuses. Safe diagnostics include known status/reason/error codes, bounded numeric usage, the requested budget and request IDs where available. Arbitrary model/server prose is excluded. The earlier generic phone message did not preserve these details, so its precise cause cannot be established retrospectively.

This adds one analysis call before the existing single image-generation call; it does not reduce the cost of the image-generation request itself. Account-specific access, real latency, cost and spatial interpretation need evaluation in the app. No real paid analysis or generation was run during implementation. See [GPT-5.4 mini](https://developers.openai.com/api/docs/models/gpt-5.4-mini), [image inputs](https://developers.openai.com/api/docs/guides/images-vision), and [structured text outputs](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses).

## OpenAI integration

The app submits one multipart image-generation request directly to `https://api.openai.com/v1/images/edits` with the prepared PNG and the fixed prompt from `ArchitecturePrompt.kt`. Advanced appends weather/snow and any selected environmental location from `EnvironmentPrompt.kt`. Only with both a location and a confirmed north arrow does it first perform the separate orientation analysis above, append computed solar and scene instructions, and add `north-orientation-guide.png` as the second `image[]`. All other image-generation parameters remain:

| Field | Value |
| --- | --- |
| `model` | `gpt-image-2.6-sunburst` |
| `image[]` | Prepared, upright PNG |
| `n` | `1` |
| `partial_images` | `0` |
| `stream` | `true` |
| `quality` | `max` |
| `output_format` | `png` |
| `size` | `auto` |

The app reads the final PNG from the `image_edit.completed` server-sent event and closes the stream immediately after receiving it. `partial_images=0` keeps intermediate generated images disabled. A complete non-streaming JSON result (`data[0].b64_json`) is also accepted if an upstream returns that format. Automatic sizing lets the model select supported output dimensions using the reference image; output dimensions and aspect ratio are not guaranteed. No unsupported `input_fidelity`, artificial upscaling, or PNG output compression option is added. The source is not downscaled for upload. Requests are not automatically retried or reconnected, to reduce the risk of duplicate paid generations. Cancellation closes the local HTTP call; it cannot guarantee cancellation of work or billing already started by OpenAI.

References checked against official OpenAI documentation:

- [GPT Image 2.6 Sunburst](https://developers.openai.com/api/docs/models/gpt-image-2.6-sunburst)
- [Image edit endpoint](https://developers.openai.com/api/reference/resources/images/methods/edit)
- [Image edit streaming events](https://developers.openai.com/api/reference/resources/images/edit-streaming-events)
- [GPT Image 2.6 parameters](https://developers.openai.com/api/docs/guides/image-prompting)

Account access, billing limits, and model availability still depend on the user's OpenAI project.

## Connection failures

Connection failures appear in a persistent, selectable error dialog. Version 0.1.2 distinguishes waiting for response headers from waiting on an opened image stream. It reports the elapsed time, negotiated HTTP protocol, response status, and bytes read. Separate diagnostic codes identify EOF, closed sockets, invalid HTTP framing, HTTP/2 stream resets, HTTP/2 connection shutdown, DNS, TLS, timeouts, and HTTP errors. Raw exceptions, server bodies, credentials, and image contents are excluded. Close the dialog after reading or copying the message.

Each request carries a new random `X-Client-Request-Id`. The error dialog includes it and an OpenAI `x-request-id` when a recognized identifier was returned. These identifiers exist only for the active request and can help support investigate an interrupted request; they do not retrieve or resume an image generation. See OpenAI's [request debugging documentation](https://developers.openai.com/api/reference/overview#debugging-requests).

Generation status distinguishes connecting, uploading, waiting for response headers, waiting for the final image, and receiving the final image. The screen stays awake during generation while the app is visible; keep it in the foreground. There is no background generation service.

The socket connection timeout is 30 seconds, the write timeout is two minutes, and the entire request has an independent 20-minute coroutine deadline. SSE reads have no separate idle deadline. The independent deadline is necessary because OkHttp SSE exits its own full-call timeout after successful response headers. HTTP/2 connections send a transport ping every 20 seconds to keep a quiet connection active; these are protocol frames, not additional image requests. Pings do not apply to HTTP/1.1, and a missing ping reply can also reveal a lost connection. Cancellation, connection failures, and deadlines do not submit another image request automatically or switch protocols and resubmit. See the pinned [OkHttp client's ping behavior](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/OkHttpClient.kt).

The physical phone reported `CONNECTION_LOST` after 64 seconds in version 0.1.1. That code did not identify the underlying IO failure, and its waiting phase began before response headers. It does not establish a VPN, network, or OpenAI fault. The user confirmed that visualization works after installing version 0.1.2, which adds transport keepalive and more precise evidence. The original transport failure was not captured. Streaming and transport pings cannot guarantee that a network or upstream server will keep every request open.

## Privacy and image handling

- Enter the API key only in the app. **Remember on this device** is off by default. When left off, the key stays in memory, survives activity recreation through the ViewModel, and is discarded when the process ends or **Clear key** is pressed. The unsaved entry never enters saved-instance state or a saved-state handle. Managed runtime and HTTP strings cannot provide guaranteed immediate memory erasure.
- When remembering is selected, version 0.1.3 encrypts the API key with AES-256-GCM, a fresh random IV per save, and a non-exportable encryption key generated in Android Keystore. Only the version, IV, and authenticated ciphertext are stored in private `noBackupFilesDir`, outside the working-image directory. No plaintext preferences, database, cloud sync, or credential backup is added. The saved key is loaded automatically when a new app session starts. Storage and Keystore operations run off the main thread. Hardware protection depends on the device; it is not assumed. See [Android Keystore](https://developer.android.com/privacy-and-security/keystore) and [backup exclusions](https://developer.android.com/identity/data/autobackup).
- **Clear key** clears session memory and removes the encrypted record and its Keystore key. Choosing session-only entry when a saved copy exists also removes that saved copy. Unreadable or missing encryption keys produce a safe error and allow clearing or replacing the saved record. Clearing working images or ending a session leaves an explicitly remembered key available for the next session.
- Image requests and the API credential target OpenAI only, over HTTPS. Their HTTP redirects, automatic connection retries, and SSE reconnection are disabled. Optional Advanced place searches send the typed name to Open-Meteo/GeoNames and Android's geocoding provider. Resolving a device-geocoded result's time zone sends its coordinates to Open-Meteo; only the returned zone identifier is used, never its snapped weather-grid coordinates or current offset. The app does not request GPS access. No images or OpenAI credentials are sent to place providers. Manual coordinates/time-zone entry and pole aliases work without these lookups. No network logging, analytics, crash reporting, or raw server errors are included.
- Direct API-key use in an Android app is intended for personal development and testing. It is not secure credential handling for public distribution. The project intentionally has no server or proxy.
- Working images live in private, non-backup storage for the current session. The original bytes are never overwritten. A new session clears old working files; starting a new image discards the previous working images. Export a result before ending the session.
- Backups and device transfer are excluded. The key dialog blocks screenshots and screen recording, and autofill is disabled. A T3 emulator feed may consequently hide the key dialog while it is open; do not remove this protection while entering credentials.
- Previews, the optional orientation guide and orientation-analysis inputs are sampled to limit memory use. The authoritative image-generation input is not sampled. Crop and projective correction run locally at source resolution. Images exceeding available processing memory are rejected with a message rather than silently downscaled. Original imports are limited to 100 MB; prepared image-generation inputs must be below 50 MB each. Decoder and response limits prevent unbounded image allocation.
- The returned PNG is decoded to an internal file and copied unchanged to MediaStore. Neither comparison nor export changes its stored pixels or native resolution. Unfinished gallery entries are removed if export fails.
- No real project images, API keys, signing keys, environment files, local logs, or machine-specific paths belong in Git. The local requirements file and private image folders are ignored.

## Validation

Local tests cover rectangular and projective selection, aspect-ratio fitting, source preservation, crop pixels, EXIF rotation, multipart request fields, PNG response decoding, redacted errors, request cancellation, private import, and byte-for-byte MediaStore export with cleanup on failure. API fixtures use local interceptors and loopback TLS servers with synthetic images and placeholder credentials. Transport tests cover quiet 70-second waits before and after headers over both HTTP/1.1 and HTTP/2, disconnection after upload, HTTP/2 reset, and truncated response bodies. They do not call OpenAI or use real credentials. The loopback servers are test fixtures only; the app has no backend.

See [VALIDATION.md](VALIDATION.md) for the checks actually completed and remaining device checks. A paid request must be tested manually in the app with the user's own key. No simulated generation is included in the app.
