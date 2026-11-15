import hashlib
import tempfile
import unittest
import zipfile
from pathlib import Path

from build_16k_runtime import check_hash, extract_selected


class RuntimeSourcesTest(unittest.TestCase):
    def test_rejects_changed_source(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "source.zip"
            path.write_bytes(b"wrong")
            spec = {"size": 5, "sha256": hashlib.sha256(b"right").hexdigest()}
            with self.assertRaisesRegex(ValueError, "checksum"):
                check_hash(path, spec)

    def test_rejects_archive_traversal(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "source.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("python/../../escape", b"bad")
            with self.assertRaisesRegex(ValueError, "Unsafe"):
                extract_selected(path, Path(temp) / "runtime", lambda name: name.startswith("python/"))

    def test_selective_extraction_preserves_executable_permissions(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            path = root / "source.zip"
            with zipfile.ZipFile(path, "w") as archive:
                info = zipfile.ZipInfo("python/bin/python3")
                info.external_attr = 0o100755 << 16
                archive.writestr(info, b"launcher")
                archive.writestr("agent/old-agent", b"old")
            extract_selected(path, root / "runtime", lambda name: name.startswith("python/"))
            self.assertEqual(0o755, (root / "runtime/python/bin/python3").stat().st_mode & 0o777)
            self.assertFalse((root / "runtime/agent").exists())


if __name__ == "__main__":
    unittest.main()
