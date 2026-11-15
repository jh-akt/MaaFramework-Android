# Android runtime 16 KiB packaging

The `android-runtime-5.16.1-r1` runtime uses the official MaaFramework 5.16.1
Android arm64 library set. `tools/runtime-sources.lock.json` pins upstream URLs,
sizes and SHA-256 values. Archives have a separate checksum and carry a portable
`runtime-provenance.json`; no developer paths or signing keys are included.

## Build

Use Python 3.12+, Android NDK 27.0.12077973 and `ANDROID_HOME`. For Go agents,
use Go 1.26.10. Build into a new or empty directory:

```sh
python3 tools/build_16k_runtime.py --kind python --output dist/python-runtime
python3 tools/package_android_runtime.py --runtime-dir dist/python-runtime \
  --output dist/maaframework-python-android-arm64-v8a-5.16.1.zip
```

The Python runtime retains CPython 3.14.4 and its existing standard library and
fallback packages from the pinned v1 archive. The launcher is relinked for 16 KiB
pages; the MaaFw binding is upgraded to 5.16.1 and patched for Android platform
detection. Only Python source and metadata are taken from the PyPI wheel, never
its manylinux binaries. This retains the legacy minimal NumPy shim; it does not
add full NumPy/Pillow support. Project Python agents still come from resources.
Unused old Go executables, CLI tools and demo plugins are omitted.

Go host apps supply their own source lock containing `repository`, `commit` and
`path` (module directory), optionally with an existing checkout for offline use:

```sh
python3 tools/build_16k_runtime.py --kind go \
  --go-source-lock ../MaaEnd-Android/tools/agent-source.lock.json \
  --go-source-repo ../MaaEnd --output dist/go-runtime
```

Source is exported at the locked commit, ignoring checkout modifications.
Agents and the shared compatibility runner use Android PIE executables linked
with 16 KiB max/common page sizes. The Go module no longer uses a sibling project
`replace` directive. The runner reports the loaded engine version dynamically.

## Verification and host integration

```sh
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/verify_android_elf.py --runtime-dir dist/python-runtime
python3 tools/verify_android_elf.py --apk path-relative-to-repo/app-debug.apk
```

The verifier checks all ELF payloads, including executables and nested Python
extensions in assets, as well as JNI libraries. It rejects 4 KiB LOAD alignment,
incongruent offsets and unaligned RELRO tails that share writable pages. Runtime
packaging runs this check by default. Host CI must also check the final APK after
AGP stripping/packaging, and use `zipalign -c -P 16 4` to check ZIP alignment.

Set `maaRuntime.releaseTag`, `releaseAsset` and `sha256` to the published archive.
Use a new release tag for any changed bytes; do not overwrite v1 or previous
runtime releases. App resource auto-updates/local overlays remain independent
of this native runtime update.

These are static compatibility checks. They do not replace running the app and
its game tasks on a physical or emulated 16 KiB Android system.
