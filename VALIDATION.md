# Vizruna — Android validation record, version 0.1.5

Version 0.1.5 adopts the Vizruna display name and export naming. Its debug build, lint checks and all twelve existing Android device tests passed on Stillword_Pixel. The Android application ID, signing identity, encrypted credential file, Keystore alias and authenticated-data string are unchanged, preserving existing installations and remembered keys. Windows uses the Vizruna name while retaining its original DPAPI entropy and data location for the same reason.

## Completed locally

- Debug APK built successfully with Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.2.21, and Android SDK 36.
- All 35 local JUnit/Robolectric tests passed for version 0.1.3 with zero failures, errors, or skipped tests. They were not rerun for the layout-only 0.1.4 update. Android graphics and storage tests use Robolectric API 35; these are not emulator or physical-device tests.
- `lintDebug` completed with zero errors and 18 warnings. The warnings concern newer available dependency versions and one optional KTX style suggestion. Stable, compatible dependency versions remain pinned intentionally.
- The downloaded Gradle 8.13 distribution matched its official SHA-256 checksum. The wrapper pins that checksum, and its distribution download and execution were verified.
- The ignore rules were checked for the local requirements file, SDK configuration, environment files, private images, and build output.

An intermediate local run aborted in Robolectric's native graphics library while initializing its JNI class cache on an OkHttp callback thread (`java/nio/ShortBuffer` was not found). The system core metadata confirmed SIGABRT in that library; there was no OOM event in the checked interval. API fixtures now initialize the native PNG decoder on the Robolectric test thread before network callbacks run. No system settings were changed and no core was extracted. This test-runtime failure is separate from the phone's connection error.

Executed Gradle tasks:

```sh
./gradlew :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest
```

The version 0.1.4 build, lint, and all twelve existing device tests completed successfully using the bundled wrapper. Only the requested emulator was selected. No new tests were added for this layout adjustment.

Test coverage:

- Five geometry tests: rectangular crop, independent perspective corners, invalid selections, ratio validation, and fitting differing aspect ratios without stretching.
- Five graphics tests: unchanged PNG byte preservation, crop pixels, projective correction, EXIF rotation, and rejection of an output path that would overwrite the source.
- Seventeen API tests: multipart fields and fixed prompt, exact JSON and SSE PNG decoding, chunked/multiline events, rejecting partial results, completing without waiting for server EOF, HTTP cancellation, an overall deadline after SSE headers, DNS/TLS/connect failure classification, socket timeouts during SSE and JSON reads, HTTP 504 without retries, server-error redaction, invalid images, response-size limits, closed socket/EOF/protocol classification, random client request IDs, and safe response metadata.
- Four transport tests use real loopback TLS sockets with synthetic PNGs and placeholder credentials. One test runs four 70-second quiet responses concurrently: before response headers and after headers, each over HTTP/1.1 and HTTP/2. Every case receives and preserves the exact final PNG with one POST. The other tests cover server disconnect after upload, an HTTP/2 stream reset, and a truncated JSON response. Failures are classified without resubmitting the image and without leaving a result file. These are test fixtures only, not an app backend or calls to OpenAI.
- Four storage/session tests: private import, exact PNG export with pending publication, cleanup after failed export, and clearing credentials without restoring them into a new session.

## Completed on Stillword_Pixel

All twelve instrumented tests passed on **Stillword_Pixel**, Android 16 / API 36, serial `emulator-5554`, in landscape orientation. Six workflow scenarios cover:

- CameraX capture, prepare-screen navigation, perspective corner dragging, aspect-ratio entry, password masking, activity recreation, retention of the original image and selection, and discarding the unsaved key field.
- System Photo Picker launch, cancellation, and private import of a generated app-owned gallery content URI with byte preservation. Selecting an existing photo through the system picker was not automated.
- Native projective correction and exact PNG byte export through the real Android MediaStore. Temporary gallery images were removed after the tests.
- Comparison of generated fixtures: original/result switching, opacity adjustment, different-aspect-ratio notice, pinch zoom, and view reset.
- A persistent network-failure dialog showing the expanded transport diagnostics and request IDs. The message scrolls in the landscape viewport and the Close button remains accessible.
- The masked API-key dialog, the initially unchecked Remember choice, toggling it, and submitting the selected value to an isolated component callback that cannot generate an image.

Six additional tests use real Android Keystore with unique test aliases and files, preserving any unrelated saved credentials. They verify encrypted bytes in no-backup storage, non-exportable AES key material, loading through a fresh store instance, restoration into a new ViewModel after clearing the old ViewModel, deletion of the encrypted file and Keystore key, session-only behavior, opting out of a previous remembered key, fresh encryption IVs, tamper rejection, and safe recovery when the encryption key is missing. All test credentials are nonfunctional placeholders, and temporary files and aliases are deleted after each test. Process termination was not automated; restoration is verified through fresh storage and ViewModel instances.

The emulator was already running and was identified by its AVD name. Tests used AndroidJUnitRunner against this same device. Earlier sessions rejected T3 device access; for version 0.1.4, T3's device list, open, bound agent-device command, and screenshots worked. An initial test run encountered competing UiAutomation registrations while the T3 device session was open. The session was released during the next run, and all twelve tests passed. T3 device control was then reopened for visual checks. No T3 settings or credentials were changed. Installing version 0.1.4 over the previous installed APK succeeded.

Device testing exposed a collapsed crop area in landscape. The prepare screen now places its preview and scrollable controls side by side on wide displays; narrower layouts reserve preview space above the controls. The aspect-ratio field provides a keyboard Done action. The result screen also keeps a bounded comparison area and scrollable controls.

## Landscape capture update

Version 0.1.4 moves the shutter and import controls beside the live camera preview in wide layouts. Captured-image review similarly places its actions beside the image. Both image areas use the full available height, while narrower layouts retain the controls below the image. Proportional full-frame fitting and the highest-available capture resolution remain in use.

The small live preview was reproduced visually on Stillword_Pixel before the change. T3 device control then verified the larger live preview, the camera permission button and Android permission dialog, a synthetic camera capture, its larger review view, retained capture after rotation to portrait, portrait live capture after Retake, and restoration of the landscape live view. All controls were readable and reachable. The emulator was left in landscape with the updated camera screen open. No real images, credentials, or paid API requests were used.

## Connection update

The user first reported an error approximately 45 seconds into visualization on a physical phone. The version 0.1.1 screenshot then showed `CONNECTION_LOST` after 64 seconds, during its waiting phase. That phase began immediately after upload, so it did not establish whether response headers had arrived. That code combined several IO failure types. The user reported failures on mobile data with VPN, Wi-Fi with VPN, and Wi-Fi without VPN; VPN alone therefore did not explain the report. After installing version 0.1.2, the user confirmed that visualization works. The original transport failure was not reproduced or precisely identified by the agent.

Version 0.1.1 requests a streamed final image with the same model, fixed prompt, `n=1`, `partial_images=0`, `quality=max`, and PNG output. It distinguishes upload/wait/receive status and safe failure codes, keeps the screen awake during active foreground generation, and leaves errors visible for inspection. Socket, upload, and overall request limits remain bounded; there is no automatic retry or reconnection. A coroutine deadline is independently tested because OkHttp SSE exits its full-call timeout after successful response headers.

Version 0.1.2 adds HTTP/2 transport pings every 20 seconds. It separately records waiting for headers and waiting on an opened stream, response status, negotiated protocol, bytes read, random client request IDs, and recognized OpenAI request IDs. EOF, closed sockets, invalid HTTP framing, HTTP/2 reset, and HTTP/2 connection shutdown have distinct diagnostics. Testing an actual disconnect exposed an EOF wrapped by OkHttp's plain IOException; classification now inspects a bounded cause chain without displaying exception messages. Interrupted JSON body reads remain transport failures rather than invalid PNG errors. No automatic fallback or resubmission is introduced.

The 70-second fixtures establish that the client handles long quiet responses in the local test environment. They do not reproduce the physical phone's network path or prove that keepalive fixes its failure. Protocol pings cannot prevent an upstream application from ending a request, and no finished image can be recovered from a disconnected Images API request by these changes.

## Remaining manual checks

Camera focus, screen glare, moire, physical-phone usability, other device sizes, and high-resolution memory behavior need manual checking on a physical phone and a real monitor. Capture and review were visually checked in both orientations on the emulator. The agent has not observed a real generated result or exercised its Save Image button; the comparison component and export service were tested independently on the emulator. The optional remembered-key feature has been verified on the emulator and still needs the user's physical-phone confirmation.

A successful real OpenAI image edit has not been verified by the agent. It requires the user's own API key entered only inside the app and explicit approval for a paid generation. No API key has been requested in chat, embedded in source, or used in local tests. No paid requests have been made by the agent. The app contains the actual integration, with no simulated generation workflow.
