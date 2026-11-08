# Community Catalog integration (custom Manager fork)

The Community Patch Catalog stays embedded in the existing Sources > Add Source panel. The website remains the source of truth for catalogue content and routes under `morphe-patches.software` remain inside the WebView.

## Current implementation

- Add-source links (HTTPS and `intent://`) are intercepted in the WebView and routed into the Manager's existing source validation and pending-source list.
- Adding a source from the catalogue does **not** close the catalogue. Users can continue browsing and queue additional sources.
- The pending list shows queued sources, allows removing them, and submits the selected sources together through the existing native Add Source flow.
- Existing sources are labelled `Already Added`; queued sources are labelled `Queued`.
- GitHub and GitLab repository links are recognized.
- The website's own navigation, Apps/Bundles tabs, sort/filter controls, search, and detail routes are left to the website's JavaScript rather than being replaced with a simplified native catalogue.
- The injected DOM observer avoids rewriting identical button labels. This is important: unconditional `textContent` writes inside a `MutationObserver` watching `childList` can create an endless mutation loop, making the WebView unresponsive and preventing sort controls/data loading from working.
- No automatic patch downloads, patching, or installation are added.

## Build and verification status

Source changes are prepared, but a local Kotlin/Gradle build has **not** been verified in this environment. The Gradle wrapper could not download Gradle 9.8.2 because `services.gradle.org` was unreachable (`UnknownHostException`). Build the project in GitHub Actions and inspect the build log before distributing the APK.

## Follow-up items

- Test the live website's Apps/Bundles data requests and sorting on the target Android WebView version. Web console messages are logged under `MorpheCommunityWeb` to aid diagnosis.
- Add app-scoped (`app=` / `appName=`) catalog requests to the queued batch model if the catalog exposes per-app add links; the current batch adapter queues repository URLs and uses the existing whole-source Add Source flow.
- Implement persisted source-add defaults (choose apps / prerelease) as a separate native Settings feature; see `CUSTOM_MANAGER_FUTURE_SETTINGS_SPEC.md`.
