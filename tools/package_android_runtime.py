import argparse
import hashlib
import json
import stat
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_RUNTIME_DIR = PROJECT_ROOT / "runtime"
DEFAULT_OUTPUT = PROJECT_ROOT / "dist" / "maaframework-android-runtime-arm64-v8a.zip"
EXCLUDED_NAMES = {".DS_Store"}


def iter_runtime_files(runtime_dir: Path) -> list[Path]:
    return sorted(
        path
        for path in runtime_dir.rglob("*")
        if path.is_file() and path.name not in EXCLUDED_NAMES
    )


def ensure_required_runtime(runtime_dir: Path) -> None:
    maafw_dir = runtime_dir / "maafw"
    agent_dir = runtime_dir / "agent"
    if not maafw_dir.is_dir():
        raise FileNotFoundError(f"required runtime directory not found: {maafw_dir}")
    if not any(path.is_file() and path.suffix == ".so" for path in maafw_dir.rglob("*")):
        raise FileNotFoundError(f"no MaaFramework shared libraries found under: {maafw_dir}")
    if not agent_dir.is_dir():
        raise FileNotFoundError(f"required runtime directory not found: {agent_dir}")
    if not any(path.is_file() for path in agent_dir.rglob("*")):
        raise FileNotFoundError(f"no runtime agent files found under: {agent_dir}")


def zip_file_with_permissions(archive: zipfile.ZipFile, source: Path, arcname: str) -> None:
    mode = source.stat().st_mode
    info = zipfile.ZipInfo.from_file(source, arcname)
    info.external_attr = (stat.S_IMODE(mode) & 0xFFFF) << 16
    with source.open("rb") as input_file:
        archive.writestr(info, input_file.read(), compress_type=zipfile.ZIP_DEFLATED)


def write_checksum(output_path: Path) -> Path:
    digest = hashlib.sha256()
    with output_path.open("rb") as input_file:
        for chunk in iter(lambda: input_file.read(1024 * 1024), b""):
            digest.update(chunk)
    checksum_path = output_path.with_suffix(output_path.suffix + ".sha256")
    checksum_path.write_text(f"{digest.hexdigest()}  {output_path.name}\n", encoding="utf-8")
    return checksum_path


def main() -> int:
    parser = argparse.ArgumentParser(description="Package staged MaaFramework Android runtime artifacts")
    parser.add_argument("--runtime-dir", type=Path, default=DEFAULT_RUNTIME_DIR, help="Staged runtime directory")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="Output runtime zip")
    parser.add_argument("--skip-validation", action="store_true", help="Skip maafw/agent presence validation")
    args = parser.parse_args()

    runtime_dir = args.runtime_dir.resolve()
    output_path = args.output.resolve()
    if not runtime_dir.is_dir():
        raise FileNotFoundError(f"runtime dir not found: {runtime_dir}")
    if not args.skip_validation:
        ensure_required_runtime(runtime_dir)

    runtime_files = iter_runtime_files(runtime_dir)
    if not runtime_files:
        raise FileNotFoundError(f"runtime dir has no files: {runtime_dir}")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output_path, "w") as archive:
        for source in runtime_files:
            arcname = source.relative_to(runtime_dir).as_posix()
            zip_file_with_permissions(archive, source, arcname)
        metadata = {
            "generated_at": datetime.now(timezone.utc).isoformat(),
            "runtime_dir": str(runtime_dir),
            "file_count": len(runtime_files),
        }
        archive.writestr(
            "runtime-package.json",
            json.dumps(metadata, ensure_ascii=False, indent=2),
            compress_type=zipfile.ZIP_DEFLATED,
        )

    checksum_path = write_checksum(output_path)
    print(f"packaged Android runtime -> {output_path}")
    print(f"sha256 -> {checksum_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
