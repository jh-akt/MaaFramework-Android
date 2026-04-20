import argparse
import json
import os
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PROJECT_ROOT = PROJECT_ROOT.parent / "Maa_bbb"
ANDROID_RUNTIME_DIR = PROJECT_ROOT / "runtime"
RUNNER_SOURCE_DIR = PROJECT_ROOT / "tools" / "go-runner"


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
        help="Path to the MAA project repository used for go-service sources",
    )
    parser.add_argument("--go-exe", default="go", help="Go executable path")
    parser.add_argument("--maafw-dir", type=Path, help="Vendored MaaFramework Android runtime directory")
    parser.add_argument("--output", type=Path, default=ANDROID_RUNTIME_DIR, help="Android runtime output directory")
    parser.add_argument("--skip-go", action="store_true", help="Skip cross-compiling agent/go-service")
    parser.add_argument("--clear", action="store_true", help="Clear staged runtime directories before copying")
    args = parser.parse_args()

    output_dir: Path = args.output
    output_dir.mkdir(parents=True, exist_ok=True)
    project_root: Path = args.project_root
    go_service_dir = project_root / "agent" / "go-service"

    if not args.skip_go and not go_service_dir.exists():
        raise FileNotFoundError(f"go-service source dir not found: {go_service_dir}")

    if args.clear:
        for child in ("agent", "maafw"):
            path = output_dir / child
            if path.exists():
                shutil.rmtree(path)

    manifest: dict[str, object] = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "output_dir": str(output_dir),
        "project_root": str(project_root),
    }

    if not args.skip_go:
        go_binary = stage_go_service(args.go_exe, go_service_dir, output_dir / "agent")
        manifest["go_service"] = str(go_binary)
        runner_binary = stage_runner(args.go_exe, RUNNER_SOURCE_DIR, output_dir / "agent")
        manifest["runner"] = str(runner_binary)

    if args.maafw_dir:
        maafw_dir = stage_maafw(args.maafw_dir, output_dir / "maafw")
        manifest["maafw"] = str(maafw_dir)

    manifest_path = output_dir / "runtime-manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"staged Android runtime manifest -> {manifest_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
