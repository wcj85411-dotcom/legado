# Personal Reader Development Baseline

## Starting point
- Development branch: `personal-reader-development`
- Base repository: `wcj85411-dotcom/legado`
- Base commit: `330e606c4d57e08adc056ff8ab7a4132c784da9a`
- Reference app supplied by the user: 阅读 3.25, package `io.legado.app.release`, versionCode 15604.
- The APK's embedded Git revision `c8c9288e2d6b84d44d335b1c790854b54e791b7f` is not present in the available source history. This source baseline is not proven byte-for-byte equivalent to the APK.
- The app project uses `com.legado.app` as its base application ID, with distinct `.debug` and `.release` suffixes. It can coexist with the official package; it cannot overwrite it.

## Product direction
Preserve the familiar reading experience and core capabilities first: bookshelf, reader, book sources, settings, reading progress, and backup/restore. Prioritize reliable Chinese Edge TTS playback and short inter-paragraph gaps. Avoid unrelated feature additions until the baseline is stable.

## Changes made in this development branch
- Added a manual, unsigned debug-build workflow at `.github/workflows/personal-reader-debug.yml`. It does not use any release signing material.
- Added a narrow app-side Edge prefetch request in `TTSReadAloudService.kt`: when the configured engine is the dedicated Edge test package, the service requests the next paragraph during the current utterance. Other TTS engines are not changed by this code path.
- The matching Edge engine change is in the companion repository branch `personal-reader-integration`: it allows only the new reader debug/release package IDs in addition to the earlier test packages, checks the Binder caller UID's installed packages, and rejects blank or over-6000-character prefetch requests.

## Build identity and signing
- Build a separate debug package first. Do not attempt to overwrite the official app.
- The original app's signing private key is unavailable; matching its signature is not possible.
- Never use a signing key found inside a public repository. Any key or password previously committed to a public repository must be treated as compromised and must not be used for releases.
- Release signing must use a newly generated private keystore stored only in the repository owner's private GitHub Actions secrets.

## Initial security review notes
- A keystore file is present under `.github/workflows/` in the repository. Treat it as exposed; do not use it.
- The existing test workflow copies that keystore and contains a hard-coded store/key password. Do not run that workflow for release signing.
- The app's current network security configuration permits cleartext HTTP globally. This may be required by some user-configured book sources, so do not remove it blindly; later replace it with a user-visible, narrowly scoped compatibility policy and test HTTP/HTTPS sources.
- Review exported components, storage permissions, WebView/JS execution, source-rule execution, and all downloaded build dependencies before publishing.

## TTS validation still required
The current mainline TTS service has a different queue/progress architecture from the earlier prototype, so the old prototype service was not copied wholesale. The new app-side prefetch hook has not yet been compiled or device-tested. Confirm paragraph indexing in both normal and paragraph modes, and verify that stale callbacks, pause/resume, chapter changes, long paragraphs, errors, and network loss do not start duplicate or stale synthesis. The companion Edge engine build must include the matching provider allowlist change.

## Acceptance gates
1. Debug build succeeds without release credentials.
2. Existing unit tests pass.
3. Install alongside the official app under a distinct package name.
4. Verify bookshelf/source/settings/navigation before touching data migration.
5. Test Edge TTS and Android system TTS, including pause/resume, chapter changes, short paragraphs, long paragraphs, errors, and network loss.
6. Verify app signature, package ID, APK hash, and artifact provenance before sharing a test APK.
