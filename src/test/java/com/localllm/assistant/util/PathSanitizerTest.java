package com.localllm.assistant.util;

import com.localllm.assistant.exception.ConfigurationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.*;

class PathSanitizerTest {

    private PathSanitizer sanitizer;
    private final Path basePath = Paths.get("/repo/root").toAbsolutePath();

    @BeforeEach
    void setUp() {
        sanitizer = new PathSanitizer();
    }

    @Test
    void sanitizeAndValidatePath_shouldReturnResolvedPath_whenPathIsValid() {
        Path result = sanitizer.sanitizeAndValidatePath("src/Main.java", basePath);
        assertThat(result).isEqualTo(basePath.resolve("src/Main.java").normalize());
    }

    @Test
    void sanitizeAndValidatePath_shouldThrow_whenTraversalDetected() {
        assertThatThrownBy(() -> sanitizer.sanitizeAndValidatePath("../../etc/passwd", basePath))
            .isInstanceOf(ConfigurationException.class)
            .hasMessageContaining("path traversal");
    }

    @Test
    void isPathWithinBase_shouldReturnTrue_whenInside() {
        Path candidate = basePath.resolve("foo/Bar.java");
        assertThat(sanitizer.isPathWithinBase(candidate, basePath)).isTrue();
    }

    @Test
    void isPathWithinBase_shouldReturnFalse_whenOutside() {
        Path candidate = Paths.get("/tmp/other");
        assertThat(sanitizer.isPathWithinBase(candidate, basePath)).isFalse();
    }

    @Test
    void getRelativePath_shouldReturnRelativePath_whenInside() {
        Path full = basePath.resolve("a/b/c.txt");
        String rel = sanitizer.getRelativePath(full, basePath);
        assertThat(rel).isEqualTo("a/b/c.txt");
    }

    @Test
    void getRelativePath_shouldThrow_whenOutside() {
        Path full = Paths.get("/other/place/x.txt");
        assertThatThrownBy(() -> sanitizer.getRelativePath(full, basePath))
            .isInstanceOf(ConfigurationException.class);
    }
}
