# Android release updates and local overlays

Applications can extend `MaaHostActivity` and supply `hostVersionName`. The shared
`framework-ui` owns the theme, four-tab screen, keep-screen-on policy and foreground
update lifecycle. Branding remains in the application's manifest and resources.
Use `Theme.MaaFrameworkAndroid.Host` for the Activity theme. Custom hosts can keep
using `MaaHostApp`, `MaaHostTheme` and `MaaHostViewModel`; call `onHostStarted()` and
`onHostStopped()` to enable the same foreground update schedule.

When migrating an existing app, override `hostPreferencesName` with its original
SharedPreferences name. Legacy task/preset keys and v1 configuration exports are
accepted by the shared settings store. `hostPolicy` controls both display order
and task/preset execution, including any required opening task. Override
`hostUiConfig(appName)` only for project-specific screen options, such as hiding
the manual override editor. Global and resource options are edited and persisted
by the shared host; their values are applied before task options and manual
overrides. Values belonging to other resource scopes remain separate.

## Update policy

The shared settings toggle defaults to on. While the host is open, it checks every
six hours, defers checks during preparation/running/stopping and retries failed
checks after 30 minutes. A manual check ignores the interval. Turning the toggle
off stops future automatic checks, not a download already in progress. This is
foreground automatic updating, not a background job that wakes a closed app.
No account or GitHub token is required for public releases; rate-limit and network
errors are shown without replacing the last usable payload.

The same schedule also supports `github_resource_repository`: it downloads the
configured source revision and pinned submodules, applies copy mappings and any
`local_overlay_path`, then publishes the resource snapshot. This mode updates
project resources and source agents; the Android runtime stays the version bundled
in the host APK. Use the release mode below for matched resource/runtime updates.

`github_release` in `maa_project_manifest.json` selects exactly one asset from the
latest stable GitHub release using `asset_pattern`. Applications may supply
`bundled_resource_path` and `bundled_version` as an offline baseline. A release
updates **the project and its matching runtime together**, not the host APK.
Changes to the shell/JNI ABI still require a new host APK.

The framework verifies the asset size and GitHub's SHA-256 digest, resumes `.part`
downloads, extracts to staging, checks compatibility, applies local overlays,
validates the catalog/agent and atomically publishes `current`. Readers and writers
share a cross-process file lease. `previous` remains available for recovery from
an interrupted publication. A failed check, patch or validation leaves the active
payload intact. Completed downloads retain only the current archive; its hash
allows rebuilding the payload when local overlay rules change. A new host APK
invalidates downloaded payloads whose overlay or release layout fingerprint no
longer matches; it uses its bundled baseline until a compatible update is ready.

The cache is under the application's external files directory at
`maaframework-resource/<project_id>/`. Clearing it returns a bundled application
to its baseline. Runtime `config`, `logs`, `debug` and `diagnostics` are separate;
release archives and overlays cannot overwrite those directories.

## Distribution layout

`github_release.mappings` declares nested ZIP extraction. `archives` is a list of
archive entry names from outermost to innermost. `source` is an entry name or a
directory prefix ending with `/`; empty means the whole archive. `target` is a
relative destination. `include` and `exclude` are full-match regular expressions
on paths relative to `source`. Every mapping must match a file. Entries cannot
escape the destination, overlap each other, or exceed the expansion/file limits.
ZIP entries are copied as regular files, never recreated as archive symlinks.

`checks` compares a JSON `file` and object-key `path` with `expected` before
publication. Use it to pin the supported Python ABI and MaaFramework native ABI.
`required_paths` lists nonempty files and `executable_paths` names executables.
The downloaded payload must include its full `maafw` and Python/Agent runtime.
No desktop resource or runtime substitution is attempted.

## Local overlay format

`local_overlay_path` points to an application assets directory with `overlay.json`:

```json
{
  "version": 1,
  "operations": [
    {"op": "copy", "asset": "entry.py", "file": "agent/android_entry.py"},
    {"op": "json_set", "file": "interface.json", "path": ["agent"],
     "value": {"child_exec": "python3", "child_args": ["agent/android_entry.py"]}}
  ]
}
```

Operations run in order:

- `copy`: copy one overlay asset to `file`.
- `copy_project`: copy `source` within the extracted project to `file`.
- `text`: replace exactly one `old` string with `new` in `file`.
- `json_set`: set `path` to `value`; optional `expected` rejects upstream drift.
- `json_text`: apply the same exact replacement to a JSON string at `path`.
- `json_merge`: recursively merge `value` at `path`. `new_keys_only: true`
  rejects different existing values, useful for introducing pipeline nodes.
- `json_nodes`: full-match `files` regex; merge the named nodes in `value` only
  into nodes already present in each JSON file.

JSON paths are object keys, with `@key=value` selectors for exactly one array
member. An empty path means the root object. Text and JSON operations accept an
already-applied identical value, so repeated application is safe; ambiguous or
changed upstream text fails closed. Patches should be small and checked, rather
than copies of entire upstream Python modules.

The Android executor is `ProjectLocalOverlay`; the build-time equivalent is
`tools/project_overlay.py`. Both consume the same files and fingerprint. The
consumer's build should declare the overlay directory and helper as task inputs.
Application-specific rules belong in that consumer, not in framework source.
Task options and a user's manual pipeline override remain the final run layer.

## Verification

Run the framework and host unit tests plus UI compilation. `ReleaseUpdateTest`
covers nested archives, unsafe paths, size bounds, compatibility failure, partial
downloads, publication validation, and reapplying changed local patches.
An optional real-payload smoke test accepts `MAA_RELEASE_FIXTURE`,
`MAA_PROJECT_MANIFEST`, and `MAA_OVERLAY_DIR`; rerun the test task when changing
these external fixtures. Test a consumer's build and payload verifier as well.
Unit/build verification does not replace device acceptance.
