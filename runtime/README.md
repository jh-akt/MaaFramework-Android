Place staged Android runtime artifacts here before packaging or publishing a runtime release.

Expected layout:

- `agent/go-service` - Android arm64 Maa Agent service used by Go-agent projects.
- `agent/maa-go-runner` - generic Android arm64 Project Interface runner.
- `agent/main.py` plus `python/` - Python Project Interface agent entry plus bundled Android Python runtime, when needed.
- `agent/cpp-algo` or `agent/agent-service` - other Maa AgentServer-compatible native executables, when needed.
- `maafw/` - MaaFramework Android runtime files required by the agent.

This directory is intentionally text-only in git. Large runtime binaries should be staged locally, then published as a GitHub Release asset.

## Stage

Use `tools/prepare_android_runtime.py` to assemble local runtime contents:

```bash
python3 tools/prepare_android_runtime.py \
  --project-root ../Maa_bbb \
  --maafw-dir /path/to/android/maafw \
  --clear
```

Optional Python runtime inputs:

```bash
python3 tools/prepare_android_runtime.py \
  --project-root ../Maa_bbb \
  --maafw-dir /path/to/android/maafw \
  --python-android-archive /path/to/python-android-arm64.tar.gz \
  --python-wheel /path/to/maa_whl_file.whl \
  --include-python-shims \
  --clear
```

## Package

Package the staged runtime into a release asset:

```bash
python3 tools/package_android_runtime.py
```

Default output:

- `dist/maaframework-android-runtime-arm64-v8a.zip`
- `dist/maaframework-android-runtime-arm64-v8a.zip.sha256`

The zip root contains `agent/`, `maafw/`, `python/` and `runtime-manifest.json` directly. Host apps can download and unpack this asset during Gradle builds.

## Publish

Example GitHub release command:

```bash
gh release create android-runtime-v1 \
  dist/maaframework-android-runtime-arm64-v8a.zip \
  dist/maaframework-android-runtime-arm64-v8a.zip.sha256 \
  --title "Android runtime v1" \
  --notes "MaaFramework Android arm64 runtime package"
```
