import importlib.util
import unittest
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


if __name__ == "__main__":
    unittest.main()
