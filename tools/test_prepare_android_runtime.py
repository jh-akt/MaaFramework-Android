import importlib.util
import unittest
import tempfile
from pathlib import Path


SCRIPT = Path(__file__).with_name("prepare_android_runtime.py")
SPEC = importlib.util.spec_from_file_location("prepare_android_runtime", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class PrepareAndroidRuntimeTest(unittest.TestCase):
    def test_ndk_host_tags_cover_ci_and_developer_hosts(self) -> None:
        self.assertEqual("windows-x86_64", MODULE.ndk_host_tag("win32"))
        self.assertEqual("linux-x86_64", MODULE.ndk_host_tag("linux"))
        self.assertEqual("darwin-x86_64", MODULE.ndk_host_tag("darwin"))

    def test_unknown_host_is_rejected(self) -> None:
        with self.assertRaises(RuntimeError):
            MODULE.ndk_host_tag("plan9")

    def test_platform_patch_handles_adjacent_api_initialization(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            path = root / "lib/python3.14/site-packages/maa/library.py"
            path.parent.mkdir(parents=True)
            path.write_text("        platform_type = platform.system().lower()\n        cls._lib_type = ctypes.CDLL\n")
            MODULE.patch_maafw_python_for_android(root)
            MODULE.patch_maafw_python_for_android(root)
            self.assertEqual(1, path.read_text().count('if platform_type == "android":'))


if __name__ == "__main__":
    unittest.main()
