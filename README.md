# Revit AI Visualizer

Native Android and Windows apps for turning a captured or imported Autodesk Revit viewport into a photorealistic architectural visualization. Android uses Kotlin, Jetpack Compose and CameraX; Windows uses C# and WPF on .NET 10. Both connect directly to OpenAI without an application backend or database. GitHub Actions builds and tests the Windows app.

Architectural fidelity is the priority. The fixed prompt instructs the model to retain visible geometry, openings, proportions, rooflines, and the camera viewpoint. **Generative image editing cannot guarantee geometric accuracy.** Always inspect the original and overlay before using a result.

## Windows

See [Windows download, usage and build instructions](windows/README.md). The **Windows desktop** GitHub Actions workflow produces a self-contained portable x64 executable, downloadable from a successful run. No API key is needed for CI.

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
2. **Prepare:** drag the corner handles to crop. Enable **Correct perspective** for independent corner placement, in top-left, top-right, bottom-right, bottom-left order. For perspective correction, enter the actual viewport aspect ratio, such as `16:9`; leaving it blank estimates the ratio from the photographed edges and can affect proportions. Use the keyboard's **Done** action to close it. Switching correction modes resets the selection. **Visualize** asks for a masked, session-only API key before the first request. On wide screens, the preview and scrollable controls appear side by side.
3. **Result:** switch between **Original**, **Result**, and **Overlay**. Adjust original opacity and pinch/drag to zoom and pan both views together. **Save Image** writes the exact returned PNG to `Pictures/Revit AI Visualizer` through MediaStore.

In the comparison, **Original** means the prepared image actually sent to the model. The untouched capture/import remains separate. Images with different aspect ratios are centered and fitted inside the same viewport without stretching or cropping; the same zoom and pan apply to both. This is a visual check, not an automatic geometry assessment.

## OpenAI integration

The app submits one multipart request directly to `https://api.openai.com/v1/images/edits` with the prepared PNG and the fixed prompt from `ArchitecturePrompt.kt`:

| Field | Value |
| --- | --- |
| `model` | `gpt-image-2.6-sunburst` |
| `image[]` | Prepared, upright PNG |
| `n` | `1` |
| `partial_images` | `0` |
| `quality` | `max` |
| `output_format` | `png` |
| `size` | `auto` |

The non-streaming response contains `data[0].b64_json`. Automatic sizing lets the model select supported output dimensions using the reference image; output dimensions and aspect ratio are not guaranteed. No unsupported `input_fidelity`, artificial upscaling, or PNG output compression option is added. The source is not downscaled for upload. Requests are not automatically retried, including after connection failures, to reduce the risk of duplicate paid generations. Cancellation closes the local HTTP call; it cannot guarantee cancellation of work or billing already started by OpenAI.

References checked against official OpenAI documentation:

- [GPT Image 2.6 Sunburst](https://developers.openai.com/api/docs/models/gpt-image-2.6-sunburst)
- [Image edit endpoint](https://developers.openai.com/api/reference/resources/images/methods/edit)
- [GPT Image 2.6 parameters](https://developers.openai.com/api/docs/guides/image-prompting)

Account access, billing limits, and model availability still depend on the user's OpenAI project.

## Privacy and image handling

- Enter the API key only in the app. It is held in memory, survives activity recreation through the ViewModel, and is discarded when the process ends or **Clear key** is pressed. Clearing it also cancels an active generation. There is no persistent credential storage or saved-instance-state copy. Managed runtime and HTTP strings cannot provide guaranteed immediate memory erasure.
- HTTPS requests target OpenAI only. HTTP redirects and automatic connection retries are disabled. No network logging, analytics, crash reporting, or raw server errors are included.
- Direct API-key use in an Android app is intended for personal development and testing. It is not secure credential handling for public distribution. The project intentionally has no server or proxy.
- Working images live in private, non-backup storage for the current session. The original bytes are never overwritten. A new session clears old working files; starting a new image discards the previous working images. Export a result before ending the session.
- Backups and device transfer are excluded. The key dialog blocks screenshots and screen recording, and autofill is disabled. A T3 emulator feed may consequently hide the key dialog while it is open; do not remove this protection while entering credentials.
- Preview images alone are sampled to limit memory use. Crop and projective correction run locally at source resolution. Images exceeding available processing memory are rejected with a message rather than silently downscaled. Original imports are limited to 100 MB; prepared API inputs must be below 50 MB. Decoder and response limits prevent unbounded image allocation.
- The returned PNG is decoded to an internal file and copied unchanged to MediaStore. Neither comparison nor export changes its stored pixels or native resolution. Unfinished gallery entries are removed if export fails.
- No real project images, API keys, signing keys, environment files, local logs, or machine-specific paths belong in Git. The local requirements file and private image folders are ignored.

## Validation

Local tests cover rectangular and projective selection, aspect-ratio fitting, source preservation, crop pixels, EXIF rotation, multipart request fields, PNG response decoding, redacted errors, request cancellation, private import, and byte-for-byte MediaStore export with cleanup on failure. The API tests intercept requests locally; they do not call OpenAI or use real credentials.

See [VALIDATION.md](VALIDATION.md) for the checks actually completed and remaining device checks. A paid request must be tested manually in the app with the user's own key. No simulated generation is included in the app.
