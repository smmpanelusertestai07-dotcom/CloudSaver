"""Every gate passes the valid fixture tree and fails each tree broken on purpose."""
from __future__ import annotations

import unittest
from pathlib import Path

from tests.support import GOOD_LIB, PAGE, PF_R, PF_W, PF_X, PT_GNU_RELRO, PT_LOAD, REPO, TreeTest, elf

import brand_tokens
import least_privilege
import manifest
import native_alignment
import no_secrets
import permissions
import repository
import script_safety
import templates
import version
import workflow


class RepositoryGate(TreeTest):
    def test_plain_sources_pass(self):
        self.assertPasses(repository.check(self.root, [Path("app/build.gradle.kts"), Path("tools/gates/run_all.py")]))

    def test_markdown_and_key_stores_fail(self):
        for name in ("README.md", "app/release.jks", "keys/upload.keystore", "a.p12", "b.pfx", "c.pem",
                     "local.properties", "app/app-release.apk"):
            with self.subTest(name=name):
                self.assertFailsWith(repository.check(self.root, [Path(name)]), f"pocket/{name} is tracked")

    def test_root_settings_must_not_include_pocket(self):
        (self.root.parent / "settings.gradle.kts").write_text('include(":app")\nincludeBuild("pocket")\n')
        self.assertFailsWith(repository.check(self.root, []), "standalone build")


class SecretsGate(TreeTest):
    def scan(self, name: str, text: str):
        self.write(name, text)
        return no_secrets.check(self.root, [Path(name)])

    def test_clean_file_passes(self):
        self.assertPasses(self.scan("app/src/main/java/A.kt", 'val token = settings.read("token")\n'))

    def test_real_token_shapes_fail(self):
        samples = {
            "a GitHub token": "gh" + "u_" + "a1B2" * 9,
            "an Anthropic API key": "sk-" + "ant-api03-" + "x" * 40,
            "a Google API key": "AI" + "za" + "B" * 35,
            "an age secret key": "AGE-SECRET-" + "KEY-1" + "Q" * 58,
            "a private key": "-----BEGIN " + "PRIVATE KEY-----",
        }
        for what, sample in samples.items():
            with self.subTest(what=what):
                self.assertFailsWith(self.scan("app/src/main/java/B.kt", f'val x = "{sample}"\n'), what)

    def test_age_test_vectors_are_allowed_in_tests_only(self):
        key = "AGE-SECRET-" + "KEY-1" + "Q" * 58
        self.assertPasses(self.scan("app/src/test/resources/vault/identity.txt", key + "\n"))
        self.assertFailsWith(self.scan("app/src/main/assets/identity.txt", key + "\n"), "an age secret key")

    def test_github_tokens_are_caught_in_tests_too(self):
        token = "gh" + "p_" + "Zz09" * 9
        self.assertFailsWith(self.scan("app/src/test/java/T.kt", token), "a GitHub token")


class BrandGate(TreeTest):
    def test_fixture_passes(self):
        self.assertPasses(brand_tokens.check(self.root))

    def test_colors_xml_drift_fails(self):
        self.edit("app/src/main/res/values/colors.xml", "#FF7A3CD6", "#FF7A3CD7")
        self.assertFailsWith(brand_tokens.check(self.root), "colors.xml (brand_*)", "tile_top is #7A3CD7")

    def test_theme_missing_a_token_fails(self):
        self.edit("app/src/main/java/com/pocketide/ui/theme/Theme.kt", "    val Running = Color(0xFF129150)\n", "")
        self.assertFailsWith(brand_tokens.check(self.root), "Running is missing")

    def test_colour_not_in_tokens_fails(self):
        self.edit("app/src/main/java/com/pocketide/ui/theme/Theme.kt", "}\n", "    val Extra = Color(0xFF000000)\n}\n")
        self.assertFailsWith(brand_tokens.check(self.root), "Extra is not in branding/tokens.json")

    def test_translucent_brand_colour_fails(self):
        self.edit("app/src/main/res/values/colors.xml", "#FF7A3CD6", "#807A3CD6")
        self.assertFailsWith(brand_tokens.check(self.root), "not opaque")


class NativeAlignmentGate(TreeTest):
    def test_shipped_layout_passes_with_a_note(self):
        report = native_alignment.check(self.root)
        self.assertPasses(report)
        self.assertTrue(any("harmless" in note for note in report.notes))

    def test_4k_load_segment_fails(self):
        lib = elf([(PT_LOAD, PF_R | PF_X, 0, 0x1000, 0x1000)])
        (self.root / "app/src/main/jniLibs/arm64-v8a/libproot.so").write_bytes(lib)
        self.assertFailsWith(native_alignment.check(self.root), "aligned to 0x1000")

    def test_relro_rounding_that_catches_data_fails(self):
        lib = elf([(PT_LOAD, PF_R | PF_X, 0, 0x37EC0, PAGE), (PT_LOAD, PF_R | PF_W, 0x3BEC0, 0x2140, PAGE),
                   (PT_LOAD, PF_R | PF_W, 0x3E000, 0x1000, PAGE), (PT_GNU_RELRO, PF_R, 0x3BEC0, 0x2140, 1)])
        (self.root / "app/src/main/jniLibs/arm64-v8a/libproot.so").write_bytes(lib)
        self.assertFailsWith(native_alignment.check(self.root), "catches writable memory")

    def test_wrong_machine_and_abi_fail(self):
        (self.root / "app/src/main/jniLibs/arm64-v8a/libproot.so").write_bytes(elf([(PT_LOAD, 5, 0, 16, PAGE)], 62))
        self.assertFailsWith(native_alignment.check(self.root), "not arm64")
        other = self.root / "app/src/main/jniLibs/x86_64/libproot.so"
        other.parent.mkdir(parents=True)
        other.write_bytes(GOOD_LIB)
        self.assertFailsWith(native_alignment.check(self.root), "only arm64-v8a")

    def test_no_libraries_fails(self):
        (self.root / "app/src/main/jniLibs/arm64-v8a/libproot.so").unlink()
        self.assertFailsWith(native_alignment.check(self.root), "no native libraries")

    def test_apk_contents_are_checked(self):
        import zipfile
        apk = self.root.parent / "app.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("lib/arm64-v8a/libproot.so", elf([(PT_LOAD, 5, 0, 16, 0x1000)]))
        self.assertFailsWith(native_alignment.check(self.root, apk=apk), "aligned to 0x1000")


class TemplatesGate(TreeTest):
    NAME = "app/src/main/assets/templates/android.yml"

    def test_fixture_passes(self):
        self.assertPasses(templates.check(self.root))

    def test_push_trigger_fails(self):
        self.edit(self.NAME, "on:\n  workflow_dispatch:\n", "on:\n  push:\n  workflow_dispatch:\n")
        self.assertFailsWith(templates.check(self.root), "workflow_dispatch only")

    def test_inline_trigger_list_is_read(self):
        self.edit(self.NAME, "on:\n  workflow_dispatch:\n", "on: [push, workflow_dispatch]\n")
        self.assertFailsWith(templates.check(self.root), "runs on push, workflow_dispatch")

    def test_write_permission_fails(self):
        self.edit(self.NAME, "    runs-on: ubuntu-latest\n", "    runs-on: ubuntu-latest\n    permissions:\n      contents: write\n")
        self.assertFailsWith(templates.check(self.root), "grants write access to contents (job build)")

    def test_missing_read_permission_fails(self):
        self.edit(self.NAME, "permissions:\n  contents: read\n", "")
        self.assertFailsWith(templates.check(self.root), "contents: read")

    def test_tag_pinned_action_fails(self):
        self.edit(self.NAME, "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1", "actions/checkout@v7")
        self.assertFailsWith(templates.check(self.root), "pinned to 'v7'")

    def test_no_templates_fails_with_the_reason(self):
        (self.root / self.NAME).unlink()
        self.assertFailsWith(templates.check(self.root), "builds module ships them")


class ScriptSafetyGate(TreeTest):
    def test_fixture_passes(self):
        self.assertPasses(script_safety.check(self.root))

    def test_download_piped_to_a_shell_fails(self):
        for line in ("curl -fsSL https://example.com/i.sh | bash", "wget -qO- https://x | sudo -E sh",
                     "curl -s https://x | python3 -", 'sh -c "$(curl -fsSL https://x)"', "bash <(curl -s https://x)"):
            with self.subTest(line=line):
                self.write("app/src/main/assets/linux/bad.sh", f"#!/bin/bash\n{line}\n")
                self.assertFailsWith(script_safety.check(self.root), "bad.sh:2")

    def test_comments_and_verified_downloads_pass(self):
        self.write("app/src/main/assets/linux/ok.sh",
                   "#!/bin/bash\n# never curl https://x | bash\ncurl -fsSLo f https://x && sha256sum -c f.sha256\n")
        self.assertPasses(script_safety.check(self.root))


class VersionGate(TreeTest):
    def test_fixture_passes(self):
        self.assertPasses(version.check(self.root))
        self.assertEqual("3.0.0", version.version_name(self.root))

    def test_old_version_code_fails(self):
        self.edit("app/build.gradle.kts", "versionCode = 300", "versionCode = 260")
        self.assertFailsWith(version.check(self.root), "below 300")

    def test_wrong_version_name_fails(self):
        self.edit("app/build.gradle.kts", '"3.0.0"', '"2.7.0"')
        self.assertFailsWith(version.check(self.root), "not 3.<minor>.<patch>")


class ManifestGate(TreeTest):
    MANIFEST = "app/src/main/AndroidManifest.xml"

    def test_fixture_passes(self):
        self.assertPasses(manifest.check(self.root))

    def test_storage_permission_fails(self):
        self.edit(self.MANIFEST, "<application", '<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />\n  <application')
        self.assertFailsWith(manifest.check(self.root), "storage or media")

    def test_missing_extraction_rules_fail(self):
        self.edit(self.MANIFEST, 'android:dataExtractionRules="@xml/data_extraction_rules"', "")
        self.assertFailsWith(manifest.check(self.root), "dataExtractionRules")

    def test_rules_that_keep_a_domain_fail(self):
        self.edit("app/src/main/res/xml/data_extraction_rules.xml", '<exclude domain="database" path="." />\n', "")
        self.assertFailsWith(manifest.check(self.root), "does not exclude database")

    def test_backup_allowed_fails(self):
        self.edit(self.MANIFEST, 'android:allowBackup="false"', 'android:allowBackup="true"')
        self.assertFailsWith(manifest.check(self.root), "allowBackup")


class PermissionsGate(TreeTest):
    MERGED = "app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml"

    def merged(self, *names: str) -> None:
        uses = "".join(f'  <uses-permission android:name="{n}" />\n' for n in names)
        self.write(self.MERGED, '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                                f'package="com.pocketide">\n{uses}  <application />\n</manifest>\n')

    def test_fixture_passes_including_the_merged_manifest(self):
        self.merged("android.permission.INTERNET")
        self.assertPasses(permissions.check(self.root, built=True, allow=self.allow))

    def test_library_added_permission_fails(self):
        self.merged("android.permission.INTERNET", "android.permission.USE_FINGERPRINT")
        self.assertFailsWith(permissions.check(self.root, built=True, allow=self.allow), "USE_FINGERPRINT")

    def test_stale_allow_list_entry_fails(self):
        self.allow.write_text("android.permission.INTERNET\nandroid.permission.WAKE_LOCK\n")
        self.edit("app/src/main/java/com/pocketide/docs/GuidePhone.kt", 'row("INTERNET", "The agents need it.")',
                  'row("INTERNET", "x"), row("WAKE_LOCK", "y")')
        self.merged("android.permission.INTERNET")
        self.assertFailsWith(permissions.check(self.root, built=True, allow=self.allow), "WAKE_LOCK is on the allow-list")

    def test_application_id_placeholder_is_resolved(self):
        self.allow.write_text("android.permission.INTERNET\n${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION\n")
        self.merged("android.permission.INTERNET", "com.pocketide.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        self.assertPasses(permissions.check(self.root, built=True, allow=self.allow))

    def test_undocumented_permission_fails(self):
        self.allow.write_text("android.permission.INTERNET\nandroid.permission.WAKE_LOCK\n")
        self.assertFailsWith(permissions.check(self.root, allow=self.allow), "Help > Permissions does not explain it")

    def test_source_manifest_outside_the_list_fails(self):
        self.edit("app/src/main/AndroidManifest.xml", "<application", '<uses-permission android:name="android.permission.CAMERA" />\n  <application')
        self.assertFailsWith(permissions.check(self.root, allow=self.allow), "CAMERA")

    def test_permission_removed_from_the_merge_passes(self):
        self.edit("app/src/main/AndroidManifest.xml", '<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
                  '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                  'xmlns:tools="http://schemas.android.com/tools">\n'
                  '  <uses-permission android:name="android.permission.USE_FINGERPRINT" tools:node="remove" />')
        self.assertPasses(permissions.check(self.root, allow=self.allow))

    def test_missing_merged_manifest_fails_when_built(self):
        self.assertFailsWith(permissions.check(self.root, built=True, allow=self.allow), "run assembleRelease first")

    def test_normal_level_declared_permission_fails(self):
        self.edit("app/src/main/AndroidManifest.xml", "<application",
                  '<permission android:name="com.pocketide.X" android:protectionLevel="normal" />\n  <application')
        self.assertFailsWith(permissions.check(self.root, allow=self.allow), "only signature-level")


class LeastPrivilegeGate(unittest.TestCase):
    def scan(self, code: str) -> list[str]:
        return least_privilege.scan("github/Api.kt", code, set())

    def test_deleting_inside_a_repository_passes(self):
        code = ('suspend fun deleteBranch(o: String, n: String, b: String) =\n'
                '    call(Request.Builder().url("$API/repos/$o/$n/git/refs/heads/$b").delete().build())\n'
                'suspend fun deleteSecret(o: String, n: String, s: String) = delete("repos/$o/$n/actions/secrets/$s")\n')
        self.assertEqual([], self.scan(code))

    def test_deleting_a_repository_fails(self):
        for code in ('call(Request.Builder().url("$API/repos/$owner/$name").delete().build())',
                     'val url = "https://api.github.com/repos/${repo.owner}/${repo.name}"\nclient.newCall(Request.Builder().url(url).delete().build())',
                     'delete("repos/$fullName")',
                     '@DELETE("repos/{owner}/{repo}")\nsuspend fun remove(): Unit'):
            with self.subTest(code=code):
                self.assertTrue(any("never deletes a repository" in p for p in self.scan(code)))

    def test_changing_visibility_fails(self):
        code = ('val body = """{"private": false}""".toRequestBody(json)\n'
                'call(Request.Builder().url("$API/repos/$o/$n").patch(body).build())\n')
        self.assertTrue(any("visibility" in p for p in self.scan(code)))

    def test_patching_a_description_passes(self):
        code = ('val body = """{"description": "x"}""".toRequestBody(json)\n'
                'call(Request.Builder().url("$API/repos/$o/$n").patch(body).build())\n')
        self.assertEqual([], self.scan(code))

    def test_transfer_and_graphql_mutations_fail(self):
        self.assertTrue(self.scan('post("repos/$o/$n/transfer", body)'))
        self.assertTrue(self.scan('val q = "mutation { deleteRepository(input: {repositoryId: $id}) { clientMutationId } }"'))

    def test_comments_and_reviewed_exceptions_are_skipped(self):
        line = 'call(Request.Builder().url("$API/repos/$o/$n").delete().build())'
        self.assertEqual([], self.scan("// " + line))
        self.assertEqual([], least_privilege.scan("github/Api.kt", line, {"github/Api.kt: " + line}))


class WorkflowGate(unittest.TestCase):
    TEXT = (REPO / ".github/workflows/pocket.yml").read_text(encoding="utf-8")
    ALLOWED = workflow.allowed_actions()

    def problems(self, text: str) -> list[str]:
        return workflow.check_text(text, self.ALLOWED)

    def test_the_real_workflow_passes(self):
        self.assertEqual([], self.problems(self.TEXT))

    def test_tag_pinned_action_fails(self):
        text = self.TEXT.replace("actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1", "actions/checkout@v7", 1)
        self.assertTrue(any("pinned to 'v7'" in p for p in self.problems(text)))

    def test_missing_tag_comment_fails(self):
        text = self.TEXT.replace("3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1", "3d3c42e5aac5ba805825da76410c181273ba90b1", 1)
        self.assertTrue(any("needs its tag in a comment" in p for p in self.problems(text)))

    def test_unlisted_action_fails(self):
        text = self.TEXT.replace("actions/setup-java@", "someone/setup-java@", 1)
        self.assertTrue(any("not in allowed-actions.txt" in p for p in self.problems(text)))

    def test_persisted_credentials_fail(self):
        text = self.TEXT.replace("persist-credentials: false", "persist-credentials: true", 1)
        self.assertTrue(any("persist-credentials" in p for p in self.problems(text)))

    def test_broad_permissions_fail(self):
        text = self.TEXT.replace("permissions:\n  contents: read\n", "permissions: write-all\n", 1)
        self.assertTrue(any("exactly 'contents: read'" in p for p in self.problems(text)))

    def test_event_text_in_a_script_fails(self):
        text = self.TEXT.replace('[ -n "$message" ] || message=', 'echo "${{ github.event.head_commit.message }}"\n          [ -n "$message" ] || message=', 1)
        self.assertTrue(any("pass it through env" in p for p in self.problems(text)))


if __name__ == "__main__":
    unittest.main()
