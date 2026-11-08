# Third-party provenance and notices

## Gliff protocol adaptation

Sigil's Rust protocol module adapts a narrow subset of the schema from [Gliff](https://github.com/omacom/gliff), pinned to revision `32bb0622cf2feef69515bf8f75a836f657f06083`, specifically `crates/gliff-proto/src/msg.rs` and `clipboard.rs`. Gliff is MIT licensed, with the notice `Copyright (c) 2026 Kevin McConnell`. The full upstream license is retained in [core/UPSTREAM-LICENSE](core/UPSTREAM-LICENSE). No complete upstream engine is vendored.

That attribution applies to the upstream material. It does not establish ownership of Sigil's original code. A project-wide Sigil license and rights-holder attribution remain undecided; do not infer a grant from this notice.

## Dependencies

The [third-party/](third-party/) directory contains the dependency notice bundle and machine-readable inventories:

- `cargo-inventory.json` records all 198 registry packages in Cargo.lock, their registry checksums, declared licenses, authors, upstream repositories and retained notice files. This deliberately includes build-only and non-Android target dependencies; it is a lockfile inventory, not a claim that all 198 packages ship in the APK.
- `cargo/` retains the packaged license, copyright and NOTICE files, including ring's BoringSSL, other-bits, fiat and once_cell notices. For packages whose published crate omits a license text, `Apache-2.0.txt` supplies the Apache option declared by that package. The inventory identifies these fallbacks. Author metadata is preserved as metadata, without inventing copyright statements.
- `android-runtime-inventory.json` records the 28 components resolved by Gradle's offline `:app:dependencies --configuration releaseRuntimeClasspath` report. It includes metadata-only variant/constraint components, available artifact SHA256 values, POM license declarations and developer attribution. Build plugins, SDK tooling and JUnit are outside this runtime inventory.
- `android/` retains each runtime component's published POM and license/NOTICE files found in its cached JAR/AAR (including nested classes.jar). These POMs all declare Apache 2.0; the complete text is also supplied in `Apache-2.0.txt`.

`third-party/SHA256SUMS` records the bundled notice files and inventories.

Preserve this directory with source distributions and ship it alongside each APK release (for example as a notice archive). The app does not currently embed this complete bundle. Regenerate and review the inventories if dependency resolution changes; the final release build must match these versions and artifacts. This collection records the cached published packages; it does not establish rights in Sigil's original work.

Video uses Android's platform MediaCodec. Sigil does not bundle a separate FFmpeg/OpenH264 decoding engine. A legacy diagnostic asset, `decoder-test.h264`, is documented in source as locally generated FFmpeg/libx264 testsrc2 content. Its SHA256 is `00c8fee25ae6469164b8b560591ea81b136946544bd0b356a940f059ca091b89`; its exact generation command and distributable provenance have not been established by this review. Resolve that provenance or remove the asset and its diagnostic integration before a public build. Generating a video with an encoder does not mean that encoder binary is bundled, but it also does not settle all codec distribution questions.

Icons are drawn in Kotlin source; this review found no separate imported icon/font asset package. Visual-reference provenance and any copied source snippets still require rights-holder review before assigning a blanket license. No new license file or copyright ownership claim was invented during publication preparation.
