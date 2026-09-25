"""The companion extension's hand-over of a first prompt, run with Node and a stand-in vscode module."""

import json
import os
import shutil
import subprocess
import tempfile
import time
import unittest

from tests.rooms_support import script

NODE = shutil.which("node")

# With -e, process.argv is [node, <extension>, <folder>].
TAKE = "console.log(JSON.stringify(require(process.argv[1]).takePrompts(process.argv[2])));"


@unittest.skipIf(NODE is None, "node runs the companion")
class CompanionPromptTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.mkdtemp(prefix="bridge-")
        self.modules = tempfile.mkdtemp(prefix="modules-")
        os.makedirs(os.path.join(self.modules, "vscode"))
        with open(os.path.join(self.modules, "vscode", "index.js"), "w") as out:
            out.write("module.exports = {};\n")

    def tearDown(self):
        shutil.rmtree(self.folder)
        shutil.rmtree(self.modules)

    def drop(self, name, content):
        path = os.path.join(self.folder, name)
        with open(path, "w") as out:
            out.write(content if isinstance(content, str) else json.dumps(content))
        return path

    def take(self):
        result = subprocess.run(
            [NODE, "-e", TAKE, script(os.path.join("companion", "extension.js")), self.folder],
            env=dict(os.environ, NODE_PATH=self.modules), capture_output=True, timeout=30,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        return json.loads(result.stdout)

    def test_a_prompt_is_taken_once_and_deleted(self):
        self.drop(".prompt-0a1b2c.json", {"prompt": "Carry on with the login screen."})
        self.assertEqual(["Carry on with the login screen."], self.take())
        self.assertEqual([], os.listdir(self.folder))
        self.assertEqual([], self.take())

    def test_only_fresh_plain_files_with_a_prompt_count(self):
        self.drop("phone.sock", "not a prompt")
        self.drop(".prompt-XYZ.json", {"prompt": "wrong name"})
        self.drop(".prompt-01.json", {"prompt": 42})
        self.drop(".prompt-02.json", "{broken")
        old = self.drop(".prompt-03.json", {"prompt": "too old"})
        eleven_minutes_ago = time.time() - 11 * 60
        os.utime(old, (eleven_minutes_ago, eleven_minutes_ago))
        target = self.drop("elsewhere.json", {"prompt": "through a link"})
        os.symlink(target, os.path.join(self.folder, ".prompt-04.json"))
        self.assertEqual([], self.take())
        self.assertEqual({"elsewhere.json", ".prompt-XYZ.json", "phone.sock"}, set(os.listdir(self.folder)))


if __name__ == "__main__":
    unittest.main()
