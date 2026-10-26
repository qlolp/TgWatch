import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class AndroidSdkSetupTest(unittest.TestCase):
    def test_finds_official_commandline_tools_when_sdkmanager_is_not_on_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manager = root / "cmdline-tools/latest/bin/sdkmanager"
            manager.parent.mkdir(parents=True)
            manager.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$SDK_SETUP_TEST_ARGS"\n')
            manager.chmod(0o755)
            arguments = root / "arguments"
            result = subprocess.run(
                ["/bin/bash", str(Path(__file__).resolve().parents[1] / "ensure-android-sdk.sh")],
                env={**os.environ, "PATH": "/usr/bin:/bin", "ANDROID_HOME": directory,
                     "SDK_SETUP_TEST_ARGS": str(arguments)}, capture_output=True, text=True,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(["--install", "platforms;android-35", "platforms;android-36",
                              "platforms;android-37.0", "build-tools;35.0.0", "build-tools;36.0.0",
                              "build-tools;37.0.0"], arguments.read_text().splitlines())


if __name__ == "__main__":
    unittest.main()
