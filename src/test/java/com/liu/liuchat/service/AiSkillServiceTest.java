package com.liu.liuchat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSkillServiceTest {
    @TempDir Path root;

    @Test void loadsOnlyRequestedFolderAndReloadsChanges() throws Exception {
        Path building = Files.createDirectories(root.resolve("building"));
        Files.writeString(building.resolve("SKILL.md"), "Build safe houses");
        Files.writeString(building.resolve("details.txt"), "Use stone");
        Path refs = Files.createDirectories(building.resolve("references"));
        Files.writeString(refs.resolve("guide.md"), "Include lighting");
        Files.writeString(building.resolve("secret.json"), "should not appear");
        Files.writeString(Files.createDirectories(root.resolve("farming")).resolve("SKILL.md"), "Grow crops");
        AiSkillService service = new AiSkillService(root);
        service.reload();
        assertEquals(java.util.List.of("building", "farming"), service.names());
        String skill = service.content("building");
        assertTrue(skill.startsWith("## SKILL.md\nBuild safe houses"));
        assertTrue(skill.contains("Use stone"));
        assertTrue(skill.contains("Include lighting"));
        assertFalse(skill.contains("secret.json"));
        assertFalse(skill.contains("Grow crops"));
        assertNull(service.content("../farming"));
        Files.writeString(building.resolve("SKILL.md"), "New instructions");
        assertTrue(service.content("building").contains("Build safe houses"));
        service.reload();
        assertTrue(service.content("building").contains("New instructions"));
    }

    @Test void includesLargePrimarySkillFile() throws Exception {
        Path dir = Files.createDirectories(root.resolve("bot"));
        String description = "服务器玩法".repeat(1300);
        Files.writeString(dir.resolve("SKILL.md"), description);
        AiSkillService service = new AiSkillService(root);
        service.reload();
        assertTrue(service.content("bot").contains(description));
    }

    @Test void skipsSymlinksAndOversizedFiles() throws Exception {
        Path skill = Files.createDirectories(root.resolve("test"));
        Files.writeString(skill.resolve("SKILL.md"), "valid");
        Files.writeString(skill.resolve("large.txt"), "x".repeat(30000));
        Path outside = Files.writeString(root.resolve("outside.md"), "outside secret");
        Files.createSymbolicLink(skill.resolve("linked.md"), outside);
        AiSkillService service = new AiSkillService(root);
        service.reload();
        assertTrue(service.content("test").contains("valid"));
        assertFalse(service.content("test").contains("outside secret"));
        assertFalse(service.content("test").contains("x".repeat(100)));
    }
}
