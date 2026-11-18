# GitPush

GitHub Desktop-style pushing for Android. Pick a **folder** or a **ZIP**, choose a repository and branch,
review exactly what changed, and push — all from the phone.

* **UI:** Kotlin + Jetpack Compose (Material 3), Room, DataStore, OkHttp
* **Engine:** Rust (`rust/`), loaded over JNI. It scans, fingerprints, diffs, packs and uploads.

## How a push works

1. **Stage** – Android only gives apps `content://` URIs, so the folder is mirrored into app storage
   (incrementally: unchanged files are skipped) or the ZIP is unpacked by Rust (zip-slip safe, optional
   single-root-folder unwrap).
2. **Scan + hash** (parallel, Rust) – honours `.gitignore`, skips `.git`, computes Git blob IDs with SHA-1.
3. **Compare** – reads the branch tree from GitHub and works out added / changed / removed files.
4. **Pack** – builds only the missing blobs, the changed trees and one commit into a real Git packfile.
5. **Upload** – sends the pack over Git's smart-HTTP protocol (`git-receive-pack`) in **one atomic request**.
   No per-file API calls, so no GitHub API rate-limit trouble, and it works on empty repositories.

A foreground service keeps the push alive with a progress notification if you leave the app.

## Two ways to sign in

1. **Sign in with GitHub** (OAuth device flow, full account access). Create an OAuth App at
   <https://github.com/settings/developers>, tick **Enable Device Flow**, and paste its *Client ID* in the app.
   (Optional: add it as a repository variable named `GP_CLIENT_ID` so CI bakes it into the APK.)
2. **Access token** (API push). Classic token with `repo` (+ `workflow` to push `.github/workflows`),
   or a fine-grained token with *Contents: Read and write*.

Tokens are encrypted with an Android Keystore AES-256-GCM key.

## Releases (GitHub Actions)

1. Put these files in your GitHub repository (include the hidden `.github/` folder). Delete the old `build.yml`.
2. Open **Actions → Release → Run workflow**, type the version (for example `1.2.0`) and run it.
3. The workflow builds the Rust engine and the app, then publishes a **GitHub Release** `v1.2.0` with:
   `GitPush-v1.2.0-arm64-v8a.apk` (most phones), `…-armeabi-v7a.apk` (old 32-bit phones),
   `…-x86_64.apk`, `…-universal.apk`, and `SHA256SUMS.txt`.
4. The version you type becomes the app's version name; the version code is derived from it
   (`1.2.3` → `1002003`), so every release must be higher than the last.

The release APKs are signed with the sideload key in `keystore/` (public, fine for personal builds; every
release can update the previous one). Use the secrets `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD` to sign with your own key.

## In-app updates

GitPush looks at the latest GitHub Release of the repository it was built from (embedded by the workflow as
`UPDATE_REPO`, editable in **Settings → App updates**). When a newer version exists a banner appears:
**Download** (picks the APK for your phone's CPU, verifies its SHA-256) → **Install** (Android's installer;
the first time you must allow GitPush to install apps). Private repositories work because the signed-in
token is used to download the asset.

## Build locally

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
export ANDROID_NDK_HOME=/path/to/ndk
gradle assembleRelease          # builds the Rust libraries first; add -PskipRust to reuse them
cargo test --manifest-path rust/Cargo.toml --lib
```

## Limits (by design)

* Files above the size limit (default 50 MB, max 99 MB) are skipped; GitHub rejects files over 100 MB. No Git LFS.
* Branches with more than 100,000 files cannot be compared in one request (GitHub API limit).
* Symlinks and empty folders are not pushed. Executable bits are kept for existing files and for ZIP entries.
* A push is one commit. Rewriting history, pulling and merging are not part of this app.

## Layout

```
rust/src/   engine.rs (orchestrator)  gitobj.rs (blobs/trees/commits)  pack.rs (packfile)
            remote.rs (smart HTTP)    scan.rs  zipx.rs  pktline.rs  lib.rs (JNI)
app/        Compose UI, Room, DataStore, push coordinator + foreground service
```
