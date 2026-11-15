"""Build version-locked Android arm64 runtime variants and verify every ELF.

The Python variant retains the legacy standard library and fallback packages;
only its launcher and MaaFw binding are replaced. It is not a full NumPy runtime.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile
import tempfile
import time
from urllib.parse import urlparse
import zipfile

from prepare_android_runtime import (
    build_python_launcher, find_python_site_packages,
    patch_maafw_python_for_android, stage_go_binary,
)
from verify_android_elf import verify_runtime

TOOLS = Path(__file__).resolve().parent


def checked_source(spec, cache, override=None):
    path = override or cache / Path(urlparse(spec["url"]).path).name
    if not path.is_file():
        if override:
            raise FileNotFoundError(path)
        partial = path.with_suffix(path.suffix + ".part")
        # Start curl again on each retry so -C recalculates the retained prefix.
        # curl's internal retry can truncate data received by the failed attempt.
        for attempt in range(5):
            result = subprocess.run(["curl", "--fail", "--location", "--connect-timeout", "30",
                                     "--continue-at", "-", "--output", str(partial), spec["url"]])
            if result.returncode == 0:
                break
            if attempt == 4:
                result.check_returncode()
            time.sleep(2)
        check_hash(partial, spec)
        partial.replace(path)
    check_hash(path, spec)
    return path


def check_hash(path, spec):
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if path.stat().st_size != spec["size"] or digest != spec["sha256"]:
        raise ValueError(f"Source checksum mismatch: {path.name}")


def extract_selected(source, destination, select):
    with zipfile.ZipFile(source) as archive:
        for entry in archive.infolist():
            if entry.is_dir() or not select(entry.filename):
                continue
            name = PurePosixPath(entry.filename)
            if name.is_absolute() or ".." in name.parts or "\\" in entry.filename:
                raise ValueError(f"Unsafe archive path: {entry.filename}")
            target = destination.joinpath(*name.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(entry) as src, target.open("wb") as dst:
                shutil.copyfileobj(src, dst)
            target.chmod(0o755 if entry.external_attr >> 16 & 0o111 else 0o644)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kind", choices=("python", "go"), required=True)
    parser.add_argument("--output", type=Path, required=True, help="New or empty destination")
    parser.add_argument("--cache-dir", type=Path, default=TOOLS.parent / ".cache/runtime-sources")
    parser.add_argument("--native-archive", type=Path)
    parser.add_argument("--python-base-archive", type=Path)
    parser.add_argument("--python-wheel", type=Path)
    parser.add_argument("--go-source-repo", type=Path, help="Optional existing upstream Git checkout")
    parser.add_argument("--go-source-lock", type=Path, help="Locked Go agent repository, commit and path")
    parser.add_argument("--go", default="go")
    args = parser.parse_args()
    lock = json.loads((TOOLS / "runtime-sources.lock.json").read_text())
    output, cache = args.output.resolve(), args.cache_dir.resolve()
    if output.exists() and any(output.iterdir()):
        raise ValueError("Output must be empty; existing runtime was not changed")
    cache.mkdir(parents=True, exist_ok=True)
    os.environ["MAAFW_ANDROID_API"] = str(lock["android_api"])
    os.environ["MAAFW_NDK_VERSION"] = lock["ndk"]
    native = checked_source(lock["maafw"], cache, args.native_archive)
    with tempfile.TemporaryDirectory(prefix="runtime-build-", dir=cache) as temp:
        work = Path(temp)
        staged = work / "runtime"
        staged.mkdir()
        # Ship the complete official library set, excluding example plugins and
        # ADB helper executables for other architectures.
        extract_selected(native, work / "native",
                         lambda name: name.startswith("bin/") and name.count("/") == 1 and name.endswith(".so"))
        shutil.move(str(work / "native/bin"), staged / "maafw")
        (staged / "agent").mkdir()
        if args.kind == "python":
            base = checked_source(lock["python_base"], cache, args.python_base_archive)
            wheel = checked_source(lock["python_binding"], cache, args.python_wheel)
            extract_selected(base, staged, lambda name: name.startswith("python/"))
            python = staged / "python"
            packages = find_python_site_packages(python)
            for old in [packages / "maa", *packages.glob("maafw-*.dist-info")]:
                if old.is_dir():
                    shutil.rmtree(old)
            # The manylinux wheel supplies Python source/metadata only. Its Linux
            # binaries must never be mixed with the official Android libraries.
            extract_selected(wheel, packages,
                             lambda name: (name.startswith("maa/") and not name.startswith("maa/bin/"))
                             or name.startswith("maafw-5.16.1.dist-info/"))
            patch_maafw_python_for_android(python)
            build_python_launcher(python)
            (staged / "agent/README.txt").write_text("Python agents are provided by the project resource bundle.\n")
        else:
            if not args.go_source_lock:
                parser.error("--go-source-lock is required for Go agents")
            agent = json.loads(args.go_source_lock.read_text())
            lock["go_agent"] = agent
            expected_go = "go version go" + lock["go"] + " "
            if not subprocess.check_output([args.go, "version"], text=True).startswith(expected_go):
                raise ValueError("Use the Go toolchain version pinned in runtime-sources.lock.json")
            source = args.go_source_repo or cache / ("go-source-" + agent["commit"])
            if not source.exists():
                subprocess.run(["git", "init", str(source)], check=True)
            commit = agent["commit"]
            if subprocess.run(["git", "-C", str(source), "cat-file", "-e", commit],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
                subprocess.run(["git", "-C", str(source), "fetch", "--depth=1",
                                agent["repository"], commit], check=True)
            data = subprocess.check_output(["git", "-C", str(source), "archive", commit, agent["path"]])
            with tarfile.open(fileobj=io.BytesIO(data)) as archive:
                archive.extractall(work / "source", filter="data")
            stage_go_binary(args.go, work / "source" / agent["path"], staged / "agent/go-service")
            stage_go_binary(args.go, TOOLS / "go-runner", staged / "agent/maa-go-runner")
        count = verify_runtime(staged)
        if not count:
            raise ValueError("No runtime ELF files found")
        provenance = {"kind": args.kind, "page_size": 16384, "sources": lock}
        (staged / "runtime-provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
        if output.exists():
            output.rmdir()
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(staged), output)
    print(f"Built {args.kind} runtime; verified {count} ELF files for 16 KiB pages")


if __name__ == "__main__":
    main()
