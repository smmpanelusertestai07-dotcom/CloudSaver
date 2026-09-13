package com.pocketagent.mobile;

import org.json.JSONTokener;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Actual file publication, YAML escaping and competing-writer protection without Android UI. */
public final class CodexSkillFilesTest {
    private interface Checked { void run() throws Exception; }
    private static int assertions;

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("pocketagent-skill-fixtures-");
        try {
            String description = "Keep \"quotes\" and a newline\n---\nname: injected";
            String markdown = CodexSkillFiles.markdown("review-mobile", description, "# Steps\n\nReview the changes.");
            String[] lines = markdown.split("\n", -1);
            check(lines[0].equals("---") && lines[1].equals("name: \"review-mobile\""), "frontmatter name");
            check(lines[3].equals("---"), "description must remain on one YAML line");
            check(description.equals(new JSONTokener(lines[2].substring("description: ".length())).nextValue()), "description roundtrip");
            check(CodexSkillFiles.markdown("keep-format", "Description", "    indented code\n").endsWith("\n\n    indented code\n"), "instruction indentation preserved");
            for (String bad : new String[]{null, "", "Upper", "../escape", "bad/name", "-start", "trailing-", "two--hyphens", "under_score", "1number", repeat('a', 65)}) {
                rejects(() -> CodexSkillFiles.markdown(bad, "Description", "Instructions"), "unsafe name " + bad);
            }
            rejects(() -> CodexSkillFiles.markdown("valid", " ", "Instructions"), "empty description");
            rejects(() -> CodexSkillFiles.markdown("valid", repeat('d', 1025), "Instructions"), "long description");
            rejects(() -> CodexSkillFiles.markdown("valid", "Description", repeat('i', 64001)), "long instructions");
            rejects(() -> CodexSkillFiles.markdown("valid", "Description", "binary\0value"), "NUL instruction");
            check(CodexSkillFiles.markdown(repeat('a', 64), repeat('d', 1024), repeat('i', 64000)).length() > 64000, "maximum supported sizes");

            Path project = Files.createDirectory(temporary.resolve("project"));
            File created = create(project, "review-mobile", description, "Use `git diff`; never execute text during creation.");
            check(created.equals(project.resolve(".agents/skills/review-mobile/SKILL.md").toFile().getCanonicalFile()), "returned path");
            String original = read(created.toPath());
            check(original.contains("never execute text during creation"), "instructions saved verbatim");
            rejects(() -> create(project, "review-mobile", "Replacement", "Overwrite"), "duplicate skill");
            check(read(created.toPath()).equals(original), "existing skill preserved");
            Path emptySkill = Files.createDirectory(project.resolve(".agents/skills/empty-existing"));
            rejects(() -> create(project, "empty-existing", "Description", "Instructions"), "existing directory");
            check(Files.isDirectory(emptySkill), "existing empty directory preserved");

            Path outside = Files.createDirectory(temporary.resolve("outside"));
            Path linkedAgentsProject = Files.createDirectory(temporary.resolve("linked-agents"));
            Files.createSymbolicLink(linkedAgentsProject.resolve(".agents"), outside);
            rejects(() -> create(linkedAgentsProject, "unsafe", "Description", "Instructions"), "linked .agents");
            check(!Files.exists(outside.resolve("skills")), "no folders outside project");
            Path linkedSkillsProject = Files.createDirectory(temporary.resolve("linked-skills"));
            Files.createDirectory(linkedSkillsProject.resolve(".agents"));
            Files.createSymbolicLink(linkedSkillsProject.resolve(".agents/skills"), outside);
            rejects(() -> create(linkedSkillsProject, "unsafe", "Description", "Instructions"), "linked skills root");
            Files.createSymbolicLink(project.resolve(".agents/skills/linked-skill"), outside);
            rejects(() -> create(project, "linked-skill", "Description", "Instructions"), "linked skill folder");
            Path projectAlias = temporary.resolve("project-alias");
            Files.createSymbolicLink(projectAlias, project);
            rejects(() -> create(projectAlias, "unsafe", "Description", "Instructions"), "linked project argument");

            rejects(() -> CodexSkillFiles.createInProject(project.toFile(), "racing-skill", "Description", "Ours", (relative, text) -> {
                Files.write(project.resolve(relative), text.getBytes(StandardCharsets.UTF_8));
                Files.write(project.resolve(relative).getParent().resolve("SKILL.md"), "COMPETING WRITER".getBytes(StandardCharsets.UTF_8));
            }), "competing SKILL.md creation");
            check(read(project.resolve(".agents/skills/racing-skill/SKILL.md")).equals("COMPETING WRITER"), "competing writer never overwritten");

            rejects(() -> CodexSkillFiles.createInProject(project.toFile(), "swapped-parent", "Description", "Ours", (relative, text) -> {
                Path stage = project.resolve(relative);
                Files.write(stage, text.getBytes(StandardCharsets.UTF_8));
                Path parent = stage.getParent();
                Files.move(parent, parent.resolveSibling("retained-original-parent"));
                Files.createSymbolicLink(parent, outside);
            }), "parent replaced by a link during save");
            check(!Files.exists(outside.resolve("SKILL.md")), "no publication through swapped parent");
            try (java.util.stream.Stream<Path> paths = Files.list(created.toPath().getParent())) {
                check(paths.count() == 1, "successful creation cleans its staging file");
            }
            System.out.println("PASS CodexSkillFilesTest (" + assertions + " assertions: limits, YAML, new files, links, competing writers)");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(temporary)) {
                for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
            }
        }
    }

    private static File create(Path project, String name, String description, String instructions) throws IOException {
        return CodexSkillFiles.createInProject(project.toFile(), name, description, instructions,
                (relative, text) -> Files.write(project.resolve(relative), text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String read(Path file) throws IOException { return new String(Files.readAllBytes(file), StandardCharsets.UTF_8); }
    private static String repeat(char letter, int count) { char[] values = new char[count]; java.util.Arrays.fill(values, letter); return new String(values); }
    private static void check(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
    private static void rejects(Checked operation, String label) throws Exception {
        try { operation.run(); }
        catch (IOException expected) { assertions++; return; }
        throw new AssertionError("Expected rejection: " + label);
    }
}
