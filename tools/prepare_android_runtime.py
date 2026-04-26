import argparse
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PROJECT_ROOT = PROJECT_ROOT.parent / "Maa_bbb"
ANDROID_RUNTIME_DIR = PROJECT_ROOT / "runtime"
RUNNER_SOURCE_DIR = PROJECT_ROOT / "tools" / "go-runner"
PYTHON_LAUNCHER_SOURCE = PROJECT_ROOT / "tools" / "android-python-launcher" / "python3_main.c"
PYTHON_SHIMS_DIR = PROJECT_ROOT / "tools" / "python-shims"


def detect_android_clang() -> tuple[str, str] | tuple[None, None]:
    sdk_root = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk_root:
        sdk_root = str(Path.home() / "Library" / "Android" / "sdk")
    ndk_root = Path(sdk_root) / "ndk"
    if not ndk_root.exists():
        return None, None

    versions = sorted([p for p in ndk_root.iterdir() if p.is_dir()], reverse=True)
    for version_dir in versions:
        toolchain = version_dir / "toolchains" / "llvm" / "prebuilt" / "darwin-x86_64" / "bin"
        cc = toolchain / "aarch64-linux-android24-clang"
        cxx = toolchain / "aarch64-linux-android24-clang++"
        if cc.exists() and cxx.exists():
            return str(cc), str(cxx)

    return None, None


def run(cmd: list[str], *, cwd: Path | None = None, env: dict[str, str] | None = None) -> None:
    process = subprocess.run(
        cmd,
        cwd=cwd,
        env=env,
        text=True,
        check=False,
    )
    if process.returncode != 0:
        raise RuntimeError(f"command failed ({process.returncode}): {' '.join(cmd)}")


def stage_go_binary(go_exe: str, source_dir: Path, output_path: Path) -> Path:
    cc, cxx = detect_android_clang()
    if not cc or not cxx:
        raise RuntimeError("Android NDK clang not found. Set ANDROID_SDK_ROOT/ANDROID_HOME correctly.")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    env = {
        **os.environ,
        "GOOS": "android",
        "GOARCH": "arm64",
        "CGO_ENABLED": "1",
        "CC": cc,
        "CXX": cxx,
    }
    run(
        [go_exe, "build", "-trimpath", "-o", str(output_path), "."],
        cwd=source_dir,
        env=env,
    )
    return output_path


def stage_go_service(go_exe: str, source_dir: Path, output_dir: Path) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / "go-service"
    return stage_go_binary(go_exe, source_dir, output_path)


def stage_runner(go_exe: str, source_dir: Path, output_dir: Path) -> Path:
    output_path = output_dir / "maa-go-runner"
    return stage_go_binary(go_exe, source_dir, output_path)


def build_python_launcher(python_runtime_dir: Path) -> Path:
    cc, _ = detect_android_clang()
    if not cc:
        raise RuntimeError("Android NDK clang not found. Set ANDROID_SDK_ROOT/ANDROID_HOME correctly.")
    if not PYTHON_LAUNCHER_SOURCE.exists():
        raise FileNotFoundError(f"Python launcher source not found: {PYTHON_LAUNCHER_SOURCE}")

    include_candidates = sorted((python_runtime_dir / "include").glob("python*/Python.h"))
    if not include_candidates:
        raise FileNotFoundError(f"Python.h not found under {python_runtime_dir / 'include'}")
    include_dir = include_candidates[-1].parent

    lib_candidates = [
        candidate
        for candidate in sorted((python_runtime_dir / "lib").glob("libpython[0-9]*.so"))
        if candidate.name != "libpython3.so"
    ]
    if not lib_candidates:
        raise FileNotFoundError(f"libpython*.so not found under {python_runtime_dir / 'lib'}")
    python_lib = lib_candidates[-1]

    output_path = python_runtime_dir / "bin" / "python3"
    output_path.parent.mkdir(parents=True, exist_ok=True)
    run(
        [
            cc,
            "-I",
            str(include_dir),
            str(PYTHON_LAUNCHER_SOURCE),
            "-L",
            str(python_runtime_dir / "lib"),
            f"-l:{python_lib.name}",
            "-Wl,-rpath,$ORIGIN/../lib",
            "-o",
            str(output_path),
        ]
    )

    strip = Path(cc).with_name("llvm-strip")
    if strip.exists():
        run([str(strip), str(output_path)])
    output_path.chmod(0o755)
    return output_path


def stage_python_agent(project_root: Path, output_dir: Path) -> Path:
    source_dir = project_root / "agent"
    if not (source_dir / "main.py").exists():
        raise FileNotFoundError(f"Python agent entry not found: {source_dir / 'main.py'}")
    target_dir = output_dir / "agent"
    target_dir.mkdir(parents=True, exist_ok=True)
    shutil.copytree(source_dir, target_dir, dirs_exist_ok=True)

    requirements = project_root / "requirements.txt"
    if requirements.exists():
        shutil.copy2(requirements, output_dir / "requirements.txt")
    return target_dir / "main.py"


def stage_python_runtime(source_dir: Path, output_dir: Path, *, build_launcher: bool = False) -> Path:
    if not source_dir.exists():
        raise FileNotFoundError(f"Python runtime dir not found: {source_dir}")
    target_dir = output_dir / "python"
    if target_dir.exists():
        shutil.rmtree(target_dir)
    shutil.copytree(source_dir, target_dir)
    if build_launcher:
        build_python_launcher(target_dir)
    return target_dir


def safe_extract_tar(archive_path: Path, target_dir: Path) -> None:
    with tarfile.open(archive_path) as archive:
        target = target_dir.resolve()
        for member in archive.getmembers():
            member_path = (target_dir / member.name).resolve()
            if target != member_path and target not in member_path.parents:
                raise RuntimeError(f"unsafe tar member path: {member.name}")
        archive.extractall(target_dir)


def stage_python_android_archive(archive_path: Path, output_dir: Path) -> Path:
    if not archive_path.exists():
        raise FileNotFoundError(f"Android Python archive not found: {archive_path}")
    with tempfile.TemporaryDirectory(prefix="maafw-android-python-") as temp_name:
        temp_dir = Path(temp_name)
        safe_extract_tar(archive_path, temp_dir)
        prefix_dir = temp_dir / "prefix"
        if not prefix_dir.exists():
            raise FileNotFoundError(f"Python Android archive does not contain prefix/: {archive_path}")
        target_dir = stage_python_runtime(prefix_dir, output_dir, build_launcher=True)
        readme = temp_dir / "README.md"
        if readme.exists():
            shutil.copy2(readme, target_dir / "PYTHON-ANDROID-README.md")
        return target_dir


def find_python_site_packages(python_runtime_dir: Path) -> Path:
    candidates = sorted((python_runtime_dir / "lib").glob("python*/site-packages"))
    if not candidates:
        raise FileNotFoundError(f"site-packages not found under {python_runtime_dir / 'lib'}")
    return candidates[-1]


def stage_python_wheel(
    wheel_path: Path,
    python_runtime_dir: Path,
    *,
    exclude_prefixes: list[str],
) -> None:
    if not wheel_path.exists():
        raise FileNotFoundError(f"Python wheel not found: {wheel_path}")
    site_packages = find_python_site_packages(python_runtime_dir)
    normalized_excludes = [
        prefix.replace("\\", "/").strip("/") + "/"
        for prefix in exclude_prefixes
        if prefix.strip()
    ]
    with zipfile.ZipFile(wheel_path) as archive:
        for member in archive.infolist():
            member_name = member.filename.replace("\\", "/")
            if member.is_dir():
                continue
            if any(member_name == prefix[:-1] or member_name.startswith(prefix) for prefix in normalized_excludes):
                continue
            target_path = (site_packages / member_name).resolve()
            site_root = site_packages.resolve()
            if site_root != target_path and site_root not in target_path.parents:
                raise RuntimeError(f"unsafe wheel member path: {member.filename}")
            target_path.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(member) as source, target_path.open("wb") as target:
                shutil.copyfileobj(source, target)


def stage_python_package_dir(source_dir: Path, python_runtime_dir: Path) -> None:
    if not source_dir.exists():
        raise FileNotFoundError(f"Python package dir not found: {source_dir}")
    site_packages = find_python_site_packages(python_runtime_dir)
    for child in source_dir.iterdir():
        target = site_packages / child.name
        if target.exists():
            if target.is_dir():
                shutil.rmtree(target)
            else:
                target.unlink()
        if child.is_dir():
            shutil.copytree(child, target)
        else:
            shutil.copy2(child, target)


def patch_maafw_python_for_android(python_runtime_dir: Path) -> None:
    site_packages = find_python_site_packages(python_runtime_dir)
    library_file = site_packages / "maa" / "library.py"
    if not library_file.exists():
        return
    text = library_file.read_text(encoding="utf-8")
    needle = "        platform_type = platform.system().lower()\n\n"
    replacement = (
        "        platform_type = platform.system().lower()\n"
        "        if platform_type == \"android\":\n"
        "            platform_type = LINUX\n\n"
    )
    if replacement in text:
        return
    if needle not in text:
        raise RuntimeError(f"Cannot patch MaaFw platform detection in {library_file}")
    library_file.write_text(text.replace(needle, replacement), encoding="utf-8")


def stage_maafw(source_dir: Path, output_dir: Path) -> Path:
    if not source_dir.exists():
        raise FileNotFoundError(f"maafw source dir not found: {source_dir}")
    if output_dir.exists():
        shutil.rmtree(output_dir)
    shutil.copytree(source_dir, output_dir)
    return output_dir


def main() -> int:
    parser = argparse.ArgumentParser(description="Stage MaaFramework Android runtime artifacts")
    parser.add_argument(
        "--project-root",
        "--maaend-root",
        dest="project_root",
        type=Path,
        default=DEFAULT_PROJECT_ROOT,
        help="Path to the MAA project repository used for agent sources",
    )
    parser.add_argument("--go-exe", default="go", help="Go executable path")
    parser.add_argument("--maafw-dir", type=Path, help="Vendored MaaFramework Android runtime directory")
    parser.add_argument("--python-runtime-dir", type=Path, help="Prebuilt Android Python runtime directory to bundle")
    parser.add_argument(
        "--python-android-archive",
        type=Path,
        help="Official CPython Android embeddable archive; copies prefix/ and builds bin/python3",
    )
    parser.add_argument("--python-wheel", action="append", type=Path, default=[], help="Wheel to unpack into bundled Python site-packages")
    parser.add_argument(
        "--python-wheel-exclude",
        action="append",
        default=[],
        help="Wheel path prefix to skip while unpacking, for example maa/bin/",
    )
    parser.add_argument(
        "--python-package-dir",
        action="append",
        type=Path,
        default=[],
        help="Directory whose children should be copied into bundled Python site-packages",
    )
    parser.add_argument(
        "--include-python-shims",
        action="store_true",
        help="Copy built-in Android fallback shims from tools/python-shims into site-packages",
    )
    parser.add_argument("--output", type=Path, default=ANDROID_RUNTIME_DIR, help="Android runtime output directory")
    parser.add_argument("--skip-go", action="store_true", help="Skip cross-compiling agent/go-service")
    parser.add_argument("--skip-python-agent", action="store_true", help="Skip copying agent/main.py style Python agent")
    parser.add_argument("--skip-runner", action="store_true", help="Skip cross-compiling tools/go-runner")
    parser.add_argument("--clear", action="store_true", help="Clear staged runtime directories before copying")
    args = parser.parse_args()

    output_dir: Path = args.output
    output_dir.mkdir(parents=True, exist_ok=True)
    project_root: Path = args.project_root
    go_service_dir = project_root / "agent" / "go-service"
    python_agent_entry = project_root / "agent" / "main.py"

    if not args.skip_go and not go_service_dir.exists():
        print(f"skip go-service: source dir not found: {go_service_dir}")
    if args.python_runtime_dir and args.python_android_archive:
        raise RuntimeError("Use either --python-runtime-dir or --python-android-archive, not both.")

    if args.clear:
        for child in ("agent", "maafw", "python", "MaaAgentBinary"):
            path = output_dir / child
            if path.exists():
                shutil.rmtree(path)

    manifest: dict[str, object] = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "output_dir": str(output_dir),
        "project_root": str(project_root),
        "agents": [],
    }
    agents: list[dict[str, str]] = []

    if not args.skip_go and go_service_dir.exists():
        go_binary = stage_go_service(args.go_exe, go_service_dir, output_dir / "agent")
        manifest["go_service"] = str(go_binary)
        agents.append({"kind": "go-service", "path": str(go_binary)})

    if not args.skip_python_agent and python_agent_entry.exists():
        python_agent = stage_python_agent(project_root, output_dir)
        agents.append({"kind": "python", "path": str(python_agent)})

    if args.python_runtime_dir:
        python_runtime = stage_python_runtime(args.python_runtime_dir, output_dir)
        manifest["python_runtime"] = str(python_runtime)

    if args.python_android_archive:
        python_runtime = stage_python_android_archive(args.python_android_archive, output_dir)
        manifest["python_runtime"] = str(python_runtime)
        manifest["python_android_archive"] = str(args.python_android_archive)

    package_dirs = list(args.python_package_dir)
    if args.include_python_shims:
        package_dirs.append(PYTHON_SHIMS_DIR)
    if args.python_wheel or package_dirs:
        python_runtime = output_dir / "python"
        if not python_runtime.exists():
            raise FileNotFoundError(f"Python runtime not staged: {python_runtime}")
        for wheel_path in args.python_wheel:
            stage_python_wheel(wheel_path, python_runtime, exclude_prefixes=args.python_wheel_exclude)
        for package_dir in package_dirs:
            stage_python_package_dir(package_dir, python_runtime)
        patch_maafw_python_for_android(python_runtime)
        manifest["python_wheels"] = [str(path) for path in args.python_wheel]
        manifest["python_package_dirs"] = [str(path) for path in package_dirs]

    if not args.skip_runner:
        runner_binary = stage_runner(args.go_exe, RUNNER_SOURCE_DIR, output_dir / "agent")
        manifest["runner"] = str(runner_binary)

    if args.maafw_dir:
        maafw_dir = stage_maafw(args.maafw_dir, output_dir / "maafw")
        manifest["maafw"] = str(maafw_dir)

    manifest["agents"] = agents
    manifest_path = output_dir / "runtime-manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"staged Android runtime manifest -> {manifest_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
