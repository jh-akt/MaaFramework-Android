import importlib.util
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("package_android_runtime.py")
SPEC = importlib.util.spec_from_file_location("package_android_runtime", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class PackageAndroidRuntimeTest(unittest.TestCase):
    def test_package_metadata_does_not_leak_absolute_paths(self) -> None:
        with tempfile.TemporaryDirectory() as temp_name:
            temp = Path(temp_name)
            runtime = temp / "runtime"
            (runtime / "maafw").mkdir(parents=True)
            (runtime / "agent").mkdir()
            (runtime / "maafw" / "libMaaFramework.so").write_bytes(b"so")
            (runtime / "agent" / "go-service").write_bytes(b"agent")
            for name in ("runtime-manifest.json", "runtime-package.json"):
                (runtime / name).write_text(json.dumps({"output_dir": temp_name, "agents": [{"path": temp_name}]}))
            output = temp / "runtime.zip"

            with patch.object(
                sys,
                "argv",
                [str(SCRIPT), "--runtime-dir", str(runtime), "--output", str(output)],
            ):
                self.assertEqual(0, MODULE.main())

            with zipfile.ZipFile(output) as archive:
                metadata = json.loads(archive.read("runtime-package.json"))
                self.assertEqual(1, archive.namelist().count("runtime-package.json"))
                self.assertNotIn("runtime-manifest.json", archive.namelist())
                self.assertEqual(b"agent", archive.read("agent/go-service"))
                self.assertEqual(b"so", archive.read("maafw/libMaaFramework.so"))
            self.assertEqual("runtime", metadata["runtime_root"])
            self.assertNotIn("runtime_dir", metadata)
            self.assertNotIn(temp_name, json.dumps(metadata))


if __name__ == "__main__":
    unittest.main()
