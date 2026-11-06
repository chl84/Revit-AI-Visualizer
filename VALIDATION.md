# Validation record

## Completed locally

- Debug APK built successfully with Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.2.21, and Android SDK 36.
- All 18 local JUnit/Robolectric tests passed with zero failures, errors, or skipped tests. Android graphics and storage tests use Robolectric API 35; these are not emulator or physical-device tests.
- `lintDebug` completed with zero errors and 15 warnings. The warnings concern newer available dependency versions and one optional KTX style suggestion. Stable, compatible dependency versions remain pinned intentionally.
- The downloaded Gradle 8.13 distribution matched its official SHA-256 checksum. The wrapper pins that checksum, and its distribution download and execution were verified.
- The ignore rules were checked for the local requirements file, SDK configuration, environment files, private images, and build output.

Executed Gradle tasks:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug
```

The final combined run used the bundled wrapper and completed successfully. Only the requested emulator was selected for device tests.

Test coverage:

- Five geometry tests: rectangular crop, independent perspective corners, invalid selections, ratio validation, and fitting differing aspect ratios without stretching.
- Five graphics tests: unchanged PNG byte preservation, crop pixels, projective correction, EXIF rotation, and rejection of an output path that would overwrite the source.
- Four API tests: documented multipart fields and fixed prompt, exact PNG decoding, redacted error handling without retries, rejection of invalid image data, and HTTP cancellation. All responses are local fixtures; there are no real API calls.
- Four storage/session tests: private import, exact PNG export with pending publication, cleanup after failed export, and clearing credentials without restoring them into a new session.

## Completed on Stillword_Pixel

All four instrumented scenarios passed on **Stillword_Pixel**, Android 16 / API 36, serial `emulator-5554`, in landscape orientation:

- CameraX capture, prepare-screen navigation, perspective corner dragging, aspect-ratio entry, password masking, activity recreation, retention of the original image and selection, and discarding the unsaved key field.
- System Photo Picker launch, cancellation, and private import of a generated app-owned gallery content URI with byte preservation. Selecting an existing photo through the system picker was not automated.
- Native projective correction and exact PNG byte export through the real Android MediaStore. Temporary gallery images were removed after the tests.
- Comparison of generated fixtures: original/result switching, opacity adjustment, different-aspect-ratio notice, pinch zoom, and view reset.

The emulator was already running and was identified by its AVD name. Tests used AndroidJUnitRunner against this same device. T3's agent device tools rejected the session even though device access was enabled; those tools were not used to claim successful device control. The underlying emulator and the normal Android test runner worked. No T3 settings or credentials were changed. The finished debug APK was installed on Stillword_Pixel after testing.

Device testing exposed a collapsed crop area in landscape. The prepare screen now places its preview and scrollable controls side by side on wide displays; narrower layouts reserve preview space above the controls. The aspect-ratio field provides a keyboard Done action. The result screen also keeps a bounded comparison area and scrollable controls.

## Remaining manual checks

Camera focus, screen glare, moire, portrait usability, other device sizes, and high-resolution memory behavior need manual checking on a physical phone and a real monitor. The complete generated-result screen and its Save Image button still need a real result; the comparison component and export service were tested independently on the emulator.

A real OpenAI image edit remains untested. It requires the user's own API key entered only inside the app and explicit approval for a paid generation. No API key has been requested in chat, embedded in source, or used in local tests. The app contains the actual integration, with no simulated generation workflow.
