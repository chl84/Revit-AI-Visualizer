# Vizruna for Windows

A native C# / WPF desktop app on .NET 10 LTS. It runs the same architecture-preserving image edit as the Android app, with desktop image import, clipboard paste, and screen-region capture. No Revit plug-in, backend, database, or embedded API key is required.

Vizruna is the new product name. The original Windows data location and DPAPI entropy remain stable, so remembered API keys survive the rename. The repository URL and internal source namespaces are unchanged.

## Download and run

Open the repository's **Actions → Vizruna Windows** workflow and select a successful run. Download **Vizruna-Windows-x64**, extract the artifact, then extract `Vizruna-Windows-x64.zip` and run `Vizruna.exe`. The published app includes the .NET runtime. It is a portable, unsigned Windows x64 executable; no installer or administrator access is needed.

Use **Open image**, **Paste image**, or **Capture screen area**. Screen capture hides the app and lets you select the viewport; Escape cancels. The original pixels are retained separately. Preparation supports rectangular crop and manual four-corner projective correction. Enter the actual viewport aspect ratio when correcting perspective, or leave it blank to estimate from the edges.

**Visualize** uses the user's paid OpenAI account. Review the result with Original / Result / Overlay, original opacity, mouse-wheel zoom, and shared drag-to-pan. Differing aspect ratios are centered and fitted proportionally. **Save PNG** copies the exact returned PNG bytes with no resizing or recompression, and refuses to overwrite the source image.

Generative image editing cannot guarantee exact geometry. Inspect openings, rooflines, facade proportions, and viewpoint against the prepared original.

## Credentials and privacy

- Enter the key only in the masked desktop dialog. The dialog requests exclusion from OS screen capture; this is best effort, not a protection against a compromised machine or every capture method.
- With **Remember on this PC** off, the key is kept in memory for the session. With it on, only DPAPI-protected data using `DataProtectionScope.CurrentUser` is written under the current user's local application data. **Clear key** deletes the saved copy and clears session memory. There is no app-provided cloud sync or plaintext credential file. Other programs running as the same Windows user can access user-scoped DPAPI; this remains a personal-use direct API client, not a secure public API-key distribution model.
- Full desktop capture is held in memory during selection; only the selected region is kept as a source image. Imported originals are copied into a private session folder and never modified. Preview sampling does not downscale the upload. Source and output processing are bounded to 40 megapixels, imports to 100 MB, API inputs below 50 MB, and network responses to 96 MiB / decoded PNGs to 64 MiB.
- Working images are stored in a unique session directory under local application data, deleted on orderly close, and abandoned sessions are cleaned on a later start. A file lock preserves other active sessions. A crash can leave working files until that cleanup. Saved PNGs and an explicitly remembered key survive normal app closure.
- No logging, telemetry, API credentials, real project images, or paid generation requests are part of CI. Only generated fixtures and nonfunctional placeholder credentials are used in tests. The app never displays raw server response bodies, exception messages containing network details, or credentials.

## OpenAI request

Direct multipart POST to `https://api.openai.com/v1/images/edits`:

| Field | Value |
| --- | --- |
| model | `gpt-image-2.6-sunburst` |
| image[] | Prepared full-resolution PNG |
| prompt | Exact architecture prompt from the Android app |
| n | `1` |
| partial_images | `0` |
| quality | `max` |
| size | `auto` |
| stream | `true` |
| output_format | `png` |

The final `image_edit.completed` event is terminal; the client returns without waiting for EOF. It also accepts a final JSON response containing exactly one `b64_json` image. Partial events are never used as a final result. The request body is one-shot to prevent transport replay, redirects are disabled, and the app never reconnects or resubmits a generation automatically. HTTP/2 transport pings occur every 20 seconds. Connection, upload and overall deadlines are 30 seconds, 2 minutes and 20 minutes respectively. Cancellation cannot guarantee stopping work or billing already started by OpenAI.

Errors contain a safe diagnostic code, request stage, elapsed time, response metadata and random client request ID, with a recognized OpenAI request ID when supplied. They do not retrieve or resume interrupted image jobs.

## Build locally

The SDK version is pinned in `global.json`. From this directory on a Windows machine with that .NET SDK installed:

```powershell
dotnet run --project tests/RevitVisualizer.Core.Tests -c Release
dotnet publish RevitVisualizer.Windows/RevitVisualizer.Windows.csproj -c Release -r win-x64 --self-contained true -o artifacts/publish
dotnet run --project tests/RevitVisualizer.Windows.Tests -c Release -- artifacts artifacts/publish/Vizruna.exe
```

The core suite also runs on Linux. The Windows projects can be cross-compiled with the SDK on Linux, but their native tests must run on Windows. Projects use framework libraries only; there are no third-party NuGet dependencies.

## GitHub CI

`.github/workflows/windows.yml` runs on Windows-source pushes to `main`, changes to the shared Android prompt checked by the parity test, pull requests touching these files, or manual dispatch. Official checkout, .NET setup, and artifact upload actions are pinned to immutable commit SHAs. Repository token permissions are read-only and checkout credentials are not persisted.

CI runs the portable regression suite, publishes the self-contained x64 app, and runs native Windows tests covering DPAPI persistence and tampering, PNG conversion and exact export, active-session cleanup, comparison rendering/opacity, zoom reset, image import and responsive crop layout. It also launches the published executable, verifies its window, and closes it. A screenshot of a synthetic preparation view and test logs are uploaded as separate evidence. No API key is needed in repository secrets.

Manual checks still needed: real Revit screen selections on mixed-DPI/multiple-monitor setups, clipboard behavior with other image applications, physical-PC usability, and a paid image edit with the user's key entered only inside the app. No paid call has been made by the agent.

References: [OpenAI image edits](https://developers.openai.com/api/reference/resources/images/methods/edit), [single-file deployment](https://learn.microsoft.com/en-us/dotnet/core/deploying/single-file/overview), [Windows DPAPI](https://learn.microsoft.com/en-us/dotnet/api/system.security.cryptography.protecteddata?view=net-10.0).
