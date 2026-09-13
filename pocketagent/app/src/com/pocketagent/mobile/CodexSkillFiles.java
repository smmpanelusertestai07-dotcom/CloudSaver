package com.pocketagent.mobile;

import android.content.Context;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Creates a new project skill; existing skills and their instructions are never replaced. */
final class CodexSkillFiles {
    static final int MAX_NAME = 64;
    static final int MAX_DESCRIPTION = 1024;
    static final int MAX_INSTRUCTIONS = 64000;

    interface TextWriter { void save(String relative, String contents) throws IOException; }

    private CodexSkillFiles() {}

    /** Must run on a worker. Returns the newly created, canonical SKILL.md file. */
    static File create(Context context, File project, String name, String description,
                       String instructions) throws IOException {
        // Reuse the workspace's complete Ubuntu-root and direct-project validation.
        WorkspaceTools.guestPath(context, project);
        final File base = project.getCanonicalFile();
        return createInProject(base, name, description, instructions,
                (relative, text) -> WorkspaceTools.saveText(context, base, relative, text));
    }

    /** Portable filesystem core, separated from Android so real host fixtures can exercise it. */
    static File createInProject(File project, String name, String description, String instructions,
                                TextWriter writer) throws IOException {
        String contents = markdown(name, description, instructions);
        if (project == null || !project.isDirectory() || Files.isSymbolicLink(project.toPath())) {
            throw new IOException("Choose a project folder before creating a skill.");
        }
        File base = project.getCanonicalFile();
        ensureDirectory(base, ".agents");
        ensureDirectory(base, ".agents/skills");
        String relativeDirectory = ".agents/skills/" + name;
        File directory = WorkspaceTools.safeChild(base, relativeDirectory);
        try {
            // Atomic reservation: even an existing empty directory belongs to its owner.
            Files.createDirectory(directory.toPath());
        } catch (FileAlreadyExistsException e) {
            throw new IOException("A skill with this name already exists. Choose a new name.", e);
        }
        Path staged = null;
        boolean published = false;
        try {
            checkedDirectory(base, relativeDirectory);
            staged = Files.createTempFile(directory.toPath(), ".pocketagent-skill-", ".tmp");
            String relativeStage = relativeDirectory + "/" + staged.getFileName();
            // Uses the existing UTF-8, bounded, fsync + atomic-save implementation.
            writer.save(relativeStage, contents);
            File stageFile = WorkspaceTools.safeChild(base, relativeStage);
            String relativeTarget = relativeDirectory + "/SKILL.md";
            File target = WorkspaceTools.safeChild(base, relativeTarget);
            checkedDirectory(base, relativeDirectory);
            if (!stageFile.isFile() || Files.isSymbolicLink(stageFile.toPath())) {
                throw new IOException("The skill's temporary file changed. Please retry.");
            }
            try {
                // link(2) creates a directory entry only when absent. Unlike a replacing
                // rename, an agent racing to create SKILL.md cannot lose its own file.
                Files.createLink(target.toPath(), stageFile.toPath());
            } catch (FileAlreadyExistsException e) {
                throw new IOException("The skill file was created by another task. Nothing was replaced.", e);
            } catch (UnsupportedOperationException e) {
                // The Android app-private ext4/f2fs workspace supports hard links. A
                // provider without links still receives Java's non-replacing copy.
                Files.copy(stageFile.toPath(), target.toPath());
            }
            published = true;
            return WorkspaceTools.safeChild(base, relativeTarget);
        } finally {
            if (staged != null) {
                try {
                    Path safeStage = WorkspaceTools.safeChild(base,
                            relativeDirectory + "/" + staged.getFileName()).toPath();
                    Files.deleteIfExists(safeStage);
                } catch (IOException ignored) { }
            }
            if (!published) {
                try {
                    // Never recurse: retain any file that another task has written.
                    Files.delete(checkedDirectory(base, relativeDirectory).toPath());
                } catch (IOException ignored) { }
            }
        }
    }

    static String markdown(String name, String description, String instructions) throws IOException {
        if (name == null || name.length() > MAX_NAME
                || !name.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")) {
            throw new IOException("Use a skill name with 1–64 lowercase letters, numbers and single hyphens; start with a letter.");
        }
        String summary = requiredText(description, MAX_DESCRIPTION, "Description");
        String body = requiredText(instructions, MAX_INSTRUCTIONS, "Instructions");
        // JSON strings are valid YAML double-quoted scalars: newlines, quotes and
        // frontmatter-looking text remain part of the description, not new keys.
        return "---\nname: " + JSONObject.quote(name) + "\ndescription: "
                + JSONObject.quote(summary) + "\n---\n\n" + body + (body.endsWith("\n") ? "" : "\n");
    }

    private static String requiredText(String value, int maximum, String label) throws IOException {
        if (value == null || value.trim().isEmpty()) throw new IOException(label + " cannot be empty.");
        if (value.length() > maximum) throw new IOException(label + " must be " + maximum + " characters or fewer.");
        if (value.indexOf('\0') >= 0) throw new IOException(label + " must contain text, not binary data.");
        return value;
    }

    private static void ensureDirectory(File base, String relative) throws IOException {
        File path = WorkspaceTools.safeChild(base, relative);
        try {
            Files.createDirectory(path.toPath());
        } catch (FileAlreadyExistsException ignored) {
            // Recheck both the file type and symlink status after a concurrent mkdir.
        }
        checkedDirectory(base, relative);
    }

    private static File checkedDirectory(File base, String relative) throws IOException {
        File path = WorkspaceTools.safeChild(base, relative);
        if (!Files.isDirectory(path.toPath(), LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path.toPath())) {
            throw new IOException("The skill folder is unavailable or is a linked path.");
        }
        return path;
    }
}
