package com.pocketide.builds

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * The workflow templates in `assets/templates/<id>.yml`. Each is added to a project as
 * `.github/workflows/pocketide-<id>.yml`, runs only when started (workflow_dispatch), reads the
 * repository only, and pins every action to a full commit SHA.
 */
object TemplateCatalog {
    const val ASSET_FOLDER = "templates"
    const val WORKFLOW_FOLDER = ".github/workflows"

    const val ANDROID_RELEASE = "android-release"
    const val ANDROID_EMULATOR = "android-emulator-tests"
    const val IOS_SIMULATOR = "ios-simulator"
    const val MACOS = "macos"
    const val WINDOWS = "windows"
    const val LINUX_X64 = "linux-x64"
    const val DOCKER = "docker-build"
    const val FLUTTER_ANDROID = "flutter-android"
    const val REACT_NATIVE_ANDROID = "react-native-android"

    private const val LINUX = "Linux (ubuntu-latest)"
    private const val MAC = "macOS (macos-latest)"
    private const val MAC_COST = " A macOS minute counts as 10 minutes of a private repository's allowance; public repositories are free."

    val all: List<BuildTemplate> = listOf(
        template(
            ANDROID_RELEASE, "Android release APK", "PocketIDE Android release", LINUX, 1,
            "Builds the release APK with Gradle. Signs it when the project has the signing Secrets; " +
                "otherwise it stays unsigned.",
        ).copy(usesSecrets = true),
        template(
            ANDROID_EMULATOR, "Android emulator tests", "PocketIDE Android emulator tests", LINUX, 1,
            "Installs the debug APK on an Android emulator, records the screen, takes a screenshot, saves the " +
                "app's log and runs the instrumented tests. One Android version by default; \"all\" runs nine and costs nine times the minutes.",
        ),
        template(
            IOS_SIMULATOR, "iPhone (iOS Simulator)", "PocketIDE iOS simulator", MAC, 10,
            "Builds the app for the iOS Simulator and brings back a screenshot and a recording. Unsigned: " +
                "installing on a real iPhone needs an Apple Developer account.$MAC_COST",
        ),
        template(
            MACOS, "Mac app", "PocketIDE macOS", MAC, 10,
            "Builds a Swift package or an Xcode project for macOS and runs its tests. Unsigned.$MAC_COST",
        ),
        template(
            WINDOWS, "Windows program", "PocketIDE Windows", "Windows (windows-latest)", 2,
            "Builds a .NET solution, a Rust crate or a Node project on Windows. A Windows minute counts as 2 minutes " +
                "of a private repository's allowance.",
        ),
        template(
            LINUX_X64, "Linux x64 computer", "PocketIDE Linux x64", LINUX, 1,
            "Runs ci/build.sh (or make) on an x86-64 Linux machine, for anything a phone's arm64 processor cannot run.",
        ),
        template(
            DOCKER, "Docker image", "PocketIDE Docker build", LINUX, 1,
            "Builds the repository's Dockerfile and brings back the build log and the image's layers. Nothing is pushed to a registry.",
        ),
        template(
            FLUTTER_ANDROID, "Flutter Android APK", "PocketIDE Flutter Android", LINUX, 1,
            "Runs the Flutter tests and builds the release APK.",
        ),
        template(
            REACT_NATIVE_ANDROID, "React Native Android APK", "PocketIDE React Native Android", LINUX, 1,
            "Installs the packages from the lock file and builds the release APK in android/.",
        ),
    )

    /**
     * Added beside the first template, unless the project already has a Dependabot file: the
     * templates pin each action to a commit, and without it those pins would never move.
     */
    const val DEPENDABOT_PATH = ".github/dependabot.yml"
    val dependabotPaths = listOf(DEPENDABOT_PATH, ".github/dependabot.yaml")
    val dependabot = """
        |# Added by PocketIDE with its build templates. They pin each action to a commit, so
        |# Dependabot opens a pull request when one of those actions has a newer release.
        |version: 2
        |updates:
        |  - package-ecosystem: github-actions
        |    directory: /
        |    schedule:
        |      interval: weekly
        |
    """.trimMargin()

    fun find(templateId: String): BuildTemplate? = all.firstOrNull { it.id == templateId }

    fun assetPath(template: BuildTemplate) = "$ASSET_FOLDER/${template.id}.yml"

    /** Where the template lives in the repository. */
    fun repoPath(template: BuildTemplate) = "$WORKFLOW_FOLDER/${template.fileName}"

    /** The template a run belongs to, by the workflow name GitHub reports. */
    fun forRunName(runName: String): BuildTemplate? = all.firstOrNull { it.workflowName == runName }

    /**
     * Templates that fit the project in [root] (a session's worktree), best first. Only the top
     * two folder levels are looked at, and links are not followed.
     */
    fun suggestedFor(root: File): List<BuildTemplate> {
        val names = topNames(root)
        fun has(name: String) = name in names
        fun hasSuffix(suffix: String) = names.any { it.endsWith(suffix) }
        val packageJson = File(root, "package.json").takeIf { plainFile(it) }?.let { runCatching { it.readText() }.getOrNull() }.orEmpty()
        val ids = buildList {
            if (has("pubspec.yaml")) addAll(listOf(FLUTTER_ANDROID, IOS_SIMULATOR))
            if (packageJson.contains("\"react-native\"")) addAll(listOf(REACT_NATIVE_ANDROID, IOS_SIMULATOR))
            if (listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts").any(::has)) {
                addAll(listOf(ANDROID_RELEASE, ANDROID_EMULATOR))
            }
            if (hasSuffix(".xcodeproj") || hasSuffix(".xcworkspace") || has("Podfile")) addAll(listOf(IOS_SIMULATOR, MACOS))
            if (has("Package.swift")) add(MACOS)
            if (has("Dockerfile")) add(DOCKER)
            if (hasSuffix(".sln") || hasSuffix(".slnx") || has("Cargo.toml")) addAll(listOf(WINDOWS, LINUX_X64))
        }.distinct()
        return if (ids.isEmpty()) all else ids.mapNotNull(::find)
    }

    /** Names at the top level and one level down ("ios/Podfile" counts as "Podfile"). */
    private fun topNames(root: File): Set<String> {
        val top = root.listFiles()?.filterNot { it.name.startsWith(".") && it.name != ".github" }.orEmpty()
        val below = top.filter { Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) && !it.name.startsWith(".") && it.name != "node_modules" }
            .flatMap { it.listFiles()?.toList().orEmpty() }
        return (top + below).map { it.name }.toSet()
    }

    private fun plainFile(file: File) = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun template(id: String, title: String, workflowName: String, runner: String, multiplier: Int, description: String) =
        BuildTemplate(
            id = id,
            title = title,
            description = description,
            fileName = "pocketide-$id.yml",
            runner = runner,
            workflowName = workflowName,
            minutesMultiplier = multiplier,
        )
}
