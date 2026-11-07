# Vizruna — Android validation record, version 0.1.10

## Version 0.1.10 build after review fixes

This APK includes the three completed review fixes: a five-minute orientation-analysis deadline with an accurate timeout message, normalization of selected schema-valid analysis variations with safe `ANALYSIS_INVALID_CONTENT` diagnostics for unusable content, and corrected multiline prompt indentation. Optional Advanced weather/snow behavior remains included; location and a confirmed north arrow are both required to trigger the analysis.

- **All 75 local tests passed**, zero failures, errors or skips, including the three review regressions and existing default/partial/full Advanced request checks.
- **All 18 instrumented tests passed on Stillword_Pixel**, Android 16 / API 36, zero failures or skips.
- APK packaging and `lintDebug` passed. Lint reports zero errors and nineteen existing warnings. Version code is 11; application, signing and credential-storage identities remain unchanged. Windows remains paused.
- Output: `app/build/outputs/apk/debug/Vizruna-0.1.10-debug.apk`, SHA-256 `4aea86f803fe86897e26a9f197df01b231e28b61631a5142a6552f86343d9841`.
- The final APK was installed and launched through T3 device control on Stillword_Pixel; the capture screen was checked in landscape.

The initial combined Gradle session was interrupted after APK packaging and the 75 local tests completed. Lint and connected Android tests were then run to successful completion separately:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest
./gradlew :app:lintDebug :app:connectedDebugAndroidTest
```

All API tests use local fixtures and placeholder credentials. No paid OpenAI request was made; real-model behavior and phone-network latency remain unverified.

## Version 0.1.9 optional location and north arrow

Advanced weather and snow no longer require a selected location or confirmed north arrow. A selected location alone supplies environmental character. An arrow alone is not sent. Only selecting both enables date/time validation, local solar calculation, the paid orientation analysis and its guide image. The simpler mode never invents computed bearings or a supplied date/time and sends exactly one clean image in one image-generation request. Advanced off retains the unchanged base prompt and default request. The UI explains these choices and supports removing either location or arrow. Cost text in the key dialog appears only when analysis will actually run.

- **58 focused local tests passed**, with zero failures, errors or skips: all Advanced and OpenAI client/transport tests. New regressions cover all four weather choices, snow on/off, no location/arrow, location only, arrow only, absence of fabricated solar/guide instructions and exclusion of stale orientation guidance. Intercepted request tests verify one image POST, one clean image, no analysis callback/progress and unchanged source bytes for partial Advanced settings. Existing full-orientation/default-flow tests pass.
- **All 18 instrumented tests passed on Stillword_Pixel**, Android 16 / API 36. The updated UI regression checks that full solar orientation still rejects an unresolved repeated DST time, accepts the selected offset, then reaches key entry after removing the arrow despite an unresolved offset. Removing the location and selecting rain/snow also reaches key entry. No key is submitted.
- `lintDebug` passed with zero errors and nineteen existing warnings. Android test compilation and debug APK packaging passed. Version code is 10; package, signing and encrypted-key identities are unchanged. Windows remains paused.
- The final APK was installed and launched through T3 device control; its capture screen was checked in landscape.
- Output: `app/build/outputs/apk/debug/Vizruna-0.1.9-debug.apk`, SHA-256 `784ddcac9c730193c4fb11908c6b87ec2eb43ca32e7a58e44abb4d73e17352e9`.

Executed successfully:

```sh
./gradlew :app:testDebugUnitTest --tests 'com.revitvisualizer.app.advanced.*' --tests 'com.revitvisualizer.app.openai.*' :app:lintDebug :app:compileDebugAndroidTestKotlin
./gradlew :app:assembleDebug :app:connectedDebugAndroidTest
```

API checks use generated PNGs, local/intercepted responses and placeholder credentials. No paid OpenAI requests were made. Generated appearance and real-model behavior remain unverified.

### 0.1.9 review fixes

- The orientation-analysis deadline is raised from two to five minutes. The non-streamed answer arrives only after the 12000-token reasoning/output budget is spent, so the earlier deadline could expire on a billed analysis that needed the budget.
- Schema-valid but benign variations no longer discard a billed analysis: an upward/downward horizontal normal without a bearing uses bearing 0 (incidence is identical), an unpaired angle becomes an unknown orientation, blank regions/notes are dropped and case-insensitive duplicate regions keep their first entry. A remaining unusable analysis reports `ANALYSIS_INVALID_CONTENT` with only the safe diagnostic fields.
- Night and twilight environment text was left indented by 12 spaces on most lines, because the multi-line lighting block disabled `trimIndent()`. The lighting block is now joined after trimming.
- **All 75 local tests passed** (three new regressions plus a deadline-message assertion). `lintDebug` reports zero errors and the nineteen existing warnings; `assembleDebug` passed. Instrumented tests were not rerun. No paid OpenAI request was made.

## Version 0.1.8 incomplete orientation analysis

The phone reported the earlier generic "OpenAI did not complete the orientation analysis" error. That client discarded the response's incomplete reason and usage, so the actual cause cannot be established from the message. The 4000-token cap was a concrete possible cause because it covered both hidden reasoning and final JSON. Version 0.1.8 raises that bounded cap to 12000, retains medium reasoning, and distinguishes output-limit, content-filter, failed and other incomplete responses. Diagnostics contain only known enums, bounded numeric usage and validated request IDs; arbitrary model/server prose and credentials are excluded. Incomplete analysis still prevents image generation, with no automatic retry or model fallback.

- **All 70 local tests passed**, zero failures, errors or skips. New regressions cover incomplete responses with completed-looking messages, output/reasoning usage, known and unknown diagnostic fields, request-ID filtering and an incomplete assistant message under a completed response. The two-stage client also verifies that output-limited analysis sends no image-generation POST and creates no result.
- **All 18 instrumented tests passed on Stillword_Pixel**, Android 16 / API 36. The new native regression checks `ANALYSIS_OUTPUT_LIMIT`, usage and request IDs, and verifies that no image-generation POST or output file follows the incomplete response.
- `assembleDebug` and `lintDebug` passed, with zero lint errors and nineteen existing warnings. The final APK (version code 9) was installed and launched successfully through T3's device control; its capture screen was checked in landscape.
- Output: `app/build/outputs/apk/debug/Vizruna-0.1.8-debug.apk`, SHA-256 `23fe72fb9f7234a749ae1b4f4f035c1c4e04956e47cfc706b365ed9168918390`.

Executed successfully:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest
```

API tests use local/intercepted fixtures and placeholder credentials. No paid OpenAI request was made; the actual phone failure and success with the higher budget have not been reproduced against the live model. The existing two-minute analysis deadline remains in effect. Android package/signing/credential identities are unchanged; Windows development remains paused.

## Version 0.1.7 APK build and emulator validation

The APK now includes the optional Advanced controls, all three review corrections, GPT-5.4 mini orientation analysis, region-specific local lighting checks and the analysis review dialog. Version code is 8. Android's package identity, signing key and credential encryption identities remain unchanged. Windows development remains paused.

- **All 67 local tests passed**, with zero failures, errors or skips, including the 70-second quiet HTTP/1.1 and HTTP/2 transport fixtures, image handling, source preservation, credential/session storage, solar calculations, orientation parsing, cancellation, two-stage request ordering and default-flow invariance.
- **All 17 instrumented tests passed on Stillword_Pixel**, Android 16 / API 36, serial `emulator-5554`. The fifteen existing tests include the expanded manual-location Activity-recreation regression. Two new native-Android checks exercise analysis followed by one image response and the lighting-review dialog, and ambiguous analysis stopping without an image-generation POST or result file. They use generated PNGs, isolated/intercepted API responses and nonfunctional placeholder credentials; they do not contact OpenAI.
- `assembleDebug` and `lintDebug` passed. Lint reports **zero errors and nineteen existing warnings** about available dependency updates and optional KTX style changes.
- The final APK was installed and launched through T3 device control. Synthetic capture, a large landscape crop preview, Advanced controls, the new paid-analysis explanation, reachable Visualize action, portrait layout and retention of Advanced after rotation were checked visually. Automatic tool scrolling failed to reach the nested list; the tool-advised direct swipe inside the controls worked. No app change was needed.
- Output: `app/build/outputs/apk/debug/Vizruna-0.1.7-debug.apk`, SHA-256 `f102484a6db5ba849d6d47d89ef9e1c52c10e8dc5af10037ad998e70ba99982a`.

Executed successfully:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest
```

No real paid orientation analysis or image generation was made. Native tests verify the app's processing of supplied fixture responses; they do not establish how accurately GPT-5.4 mini interprets the user's real scene, whether the image model follows that interpretation, or the actual paid-request latency/cost. Physical-phone and real-model evaluation remain manual checks with the user's key entered only in the app.

## Earlier source validation before the 0.1.7 APK

### Two-stage Advanced orientation — source-only checks

Advanced now performs one GPT-5.4 mini text-analysis request before the existing GPT Image 2.6 Sunburst image request. Advanced off still makes exactly one image request with the unchanged base prompt. The image model, `n=1`, streaming parameters, image ordering and original/full-resolution handling remain unchanged. Windows is paused and untouched. Existing APK hashes were checked before and after; no APK packaging or installation was performed.

- **49 targeted local tests passed**, zero failures, errors or skips: ten environment-input/prompt tests, seven solar tests, two place-search tests, two manual-draft tests, three surface-lighting tests, twenty image-client tests and five orientation-client tests.
- New fixtures verify the analysis endpoint/model, two sampled PNG inputs (including reduction from 3200×1600 to 1600×800), exact guide bytes, strict structured text schema, no tools/history/response storage, credential exclusion from the JSON body and preservation of source bytes. The analysis body streams base64 without closing the underlying JSON sink.
- The full Advanced client test observes exactly one analysis POST followed by exactly one image POST. The final image prompt contains the interpreted region labels and locally computed lighting rules. Failed or ambiguous analysis produces no image POST or result file. Cancellation after successful analysis also prevents the image POST. Existing default-flow tests still observe exactly one POST and byte-for-byte equality of the default prompt.
- Analysis fixtures cover incomplete/refused/malformed responses, invalid normals, ambiguous orientation, response/input limits, cancellation of the underlying HTTP call and a bounded deadline without retries. Errors do not expose raw model/server responses or credentials.
- Analytical surface tests for Ålesund on 2026-11-07 at 12:00 verify that a north-facing vertical gable and north-sloping roof do not receive direct south sunlight, while an unobstructed south face and upward flat roof can. They also cover grazing incidence, uncertain normals, inferred obstruction, overcast/rain and twilight. These fixtures supply known normals; they **do not test whether the real model infers those normals correctly** from the user's image.
- The inferred guide is available through **Review lighting analysis** after a successful Advanced result. Prompt/review tests verify that calculated time/place and solar data remain authoritative and that conflicting source lighting must be replaced while preserving architecture. No automatic image-quality score is added.
- `lintDebug` passed with zero errors and the existing nineteen warnings. `compileDebugAndroidTestKotlin` passed. Device execution is deferred because it requires APK packaging.

Executed successfully without building APKs:

```sh
./gradlew :app:testDebugUnitTest --tests 'com.revitvisualizer.app.advanced.*' --tests 'com.revitvisualizer.app.openai.OpenAiImagesTest' --tests 'com.revitvisualizer.app.openai.OrientationAnalysisClientTest' :app:lintDebug :app:compileDebugAndroidTestKotlin
```

No paid analysis or generation was made. Real-model spatial interpretation, latency, cost, account access and resulting image fidelity remain unverified. The new analysis needs a user-initiated real request after a future APK build; an extra AI stage does not establish physical accuracy by itself.

## Source corrections after review — APK build deferred

The three review findings are corrected in source. No new APK was built or installed, as requested; both existing APK files retain their previous SHA-256 hashes and do not contain these corrections.

- Solar bearing is now included below the horizon. Civil, nautical and astronomical twilight use it to orient diffuse horizon glow while retaining the prohibition on visible Sun, direct sunlight and solar cast shadows. Full night explicitly suppresses invented twilight glow; zenith/nadir do not receive a fictitious horizontal bearing.
- Deduplicated place results retain a complete candidate with a known time zone when available. Its own name, coordinates and elevation remain together, avoiding both an unnecessary forecast-service dependency and attaching one provider's zone to another point.
- Unconfirmed manual place fields, validation feedback and dialog visibility are owned by the session ViewModel. They survive Activity recreation and changes between responsive layouts. Confirmation, Cancel and starting a new image clear the draft appropriately; cancelling does not change the selected place.
- **38 focused local tests passed**, with zero failures or errors: twenty Advanced tests and eighteen image API tests. New regressions cover three twilight stages, full night/nadir, duplicate and nearby provider results with an unavailable forecast endpoint, draft restoration through actual Robolectric Activity recreation, and draft confirmation/cancellation/reset behavior. Requests use local fixtures only.
- `lintDebug` passed with zero errors and the existing nineteen warnings. The device workflow test now also checks all four unconfirmed manual fields after Activity recreation; `compileDebugAndroidTestKotlin` passed. The updated instrumented test has **not** been run on the emulator, because connected test execution would build APKs.

Checks executed without APK packaging:

```sh
./gradlew :app:testDebugUnitTest --tests 'com.revitvisualizer.app.advanced.*' :app:lintDebug
./gradlew :app:testDebugUnitTest --tests 'com.revitvisualizer.app.advanced.*' --tests 'com.revitvisualizer.app.openai.OpenAiImagesTest' :app:compileDebugAndroidTestKotlin
```

## Optional Advanced environment

Version 0.1.6 adds Android-only, opt-in place/date/time, weather, snow and image north-arrow controls. Windows development is paused. Windows source and the shared base prompt are unchanged. Android's application ID and remembered-key encryption identities are unchanged.

- All **50 local JUnit/Robolectric tests passed**, with zero failures, errors or skips. The original 35 checks remain; the additions cover the NREL SPA published example, exact north/south poles, midnight sun, winter darkness, twilight thresholds, southern-hemisphere illumination, fractional UTC offsets, date-line invariance, DST gaps and repeated times, place parsing, time-zone lookup fixtures, guide pixels/source-byte preservation, appended prompt constraints, default prompt equality, and multipart image ordering with exactly one output. API fixtures use synthetic images and placeholder credentials, never OpenAI.
- All **15 instrumented tests passed on Stillword_Pixel**, Android 16 / API 36. The original twelve checks remain. New scenarios cover entering a precise Bø location/time zone, optional snow, north-arrow rotation, retaining the unconfirmed arrow across Activity recreation, confirmation invalidation after cropping, returning to the unchanged standard key-dialog flow, explicit repeated-time UTC-offset selection, blocking an incomplete Advanced request before key entry, and executing the SPA library on real Android including the exact North Pole. The full suite passed in landscape; after the rotation-state fix, all fifteen passed again with the emulator in portrait.
- The NREL/TP-560-34302 reference example (2003-10-17, 12:30:30 UTC−07:00, latitude 39.742476°, longitude −105.1786°, elevation 1830.14 m) matches reference azimuth 194.34024° and geometric elevation 39.872046° within 0.005° on the JVM and Android. This checks the wrapper against a published numerical example; it does not establish a universal error bound or generated-image accuracy.
- Debug APK and `lintDebug` passed. Lint reports **0 errors and 19 warnings**: dependency update suggestions and two optional KTX style suggestions.
- T3's bound device command and screenshots verified the large landscape crop preview, a synthetic CameraX capture, Advanced controls, a real read-only **Sahara** search returning **Sahara Desert** before similarly named populated places, its resolved local-time/solar display, weather and snow controls, the full-image north editor, dragging the arrow base, and portrait/landscape layouts. No real user images or API keys were used. Live lookup availability was observed on this emulator; arbitrary place coverage and other providers are not guaranteed.
- Visual rotation checking exposed an unconfirmed-arrow reset. Its coordinates now use Compose's saveable state; the new device regression test recreates the Activity before confirming a rotated arrow and verifies the saved direction. An earlier old workflow test selected all toggleable controls; it now targets the perspective switch by its explicit accessible description.
- Public lookup checks confirmed Open-Meteo's Bø coordinates and `Europe/Oslo` zone, New York results, and coordinate-based time-zone lookup. Fixture tests ensure that snapped forecast grid coordinates and the service's current UTC offset never replace the selected point or the date-specific IANA rules. Manual coordinates/time zones and named geographic poles support operation without place lookup.

Executed successfully:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest
./gradlew :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest
```

The second run checks the arrow Activity-recreation fix; solar, networking and prompt logic are unchanged from the fifty-test local run. The final build also corrects the undefined-bearing label so the nadir is not described as an overhead Sun. No paid generation was performed. Actual model interpretation of the north arrow, environmental appearance and solar shadows still needs review with a real image and a user-initiated paid request. Geocoding/time-zone data, atmospheric refraction, unmeasured terrain and generative image interpretation limit physical accuracy. No generated-image correctness score or guarantee is claimed.

## Previous validation history


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
