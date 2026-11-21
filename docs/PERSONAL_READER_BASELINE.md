# Personal Reader Development Baseline

## Starting point
- Development branch: `personal-reader-development`
- Base repository: `wcj85411-dotcom/legado`
- Base commit: `330e606c4d57e08adc056ff8ab7a4132c784da9a`
- Reference app supplied by the user: Legado / 阅读 3.25, package `io.legado.app.release`, versionCode 15604.
- The APK's embedded Git revision `c8c9288e2d6b84d44d335b1c790854b54e791b7f` is not present in the available source repository history. The source baseline is therefore not yet proven byte-for-byte equivalent to that APK.

## Product direction
Preserve the familiar reading experience and core capabilities first: bookshelf, reader, book sources, settings, reading progress, and backup/restore. Prioritize reliable Chinese Edge TTS playback and short inter-paragraph gaps. Avoid unrelated feature additions until the baseline is stable.

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

## TTS integration plan
The current mainline TTS service has a different queue/progress architecture from the earlier prefetch prototype. Do not copy the prototype service wholesale. First map its paragraph queue, utterance IDs, callback lifecycle, pause/resume, chapter transitions, and Edge provider IPC contract. Then add narrowly scoped prefetch integration with package/UID validation and tests. Keep normal Android TTS engines working unchanged.

## Acceptance gates
1. Debug build succeeds without release credentials.
2. Existing unit tests pass.
3. Install alongside the official app under a distinct package name.
4. Verify bookshelf/source/settings/navigation before touching data migration.
5. Test Edge TTS and Android system TTS, including pause/resume, chapter changes, short paragraphs, long paragraphs, errors, and network loss.
6. Verify app signature, package ID, APK hash, and artifact provenance before sharing a test APK.
