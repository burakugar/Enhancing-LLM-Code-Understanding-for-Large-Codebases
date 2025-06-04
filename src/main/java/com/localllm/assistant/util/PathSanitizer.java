package com.localllm.assistant.util;

import com.localllm.assistant.exception.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility class for sanitizing and validating file paths to prevent path traversal attacks.
 * This class ensures that all file operations are confined to the configured code repository
 * directory and prevents access to files outside of this directory.
 */
@Component
public class PathSanitizer {

    private static final Logger log = LoggerFactory.getLogger(PathSanitizer.class);

    /**
     * Sanitizes a relative file path by:
     * 1. Normalizing the path (removing redundant elements like ./ and ../)
     * 2. Ensuring the path doesn't try to escape outside the base directory
     * 3. Validating the characters in the path
     *
     * @param relativePath The relative path to sanitize
     * @param basePath     The base directory path that all operations should be confined to
     * @return The sanitized and validated Path object
     * @throws ConfigurationException If the path is invalid or attempts to escape the base directory
     */
    public Path sanitizeAndValidatePath(String relativePath, Path basePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            throw new ConfigurationException("Path cannot be null or empty");
        }

        if (basePath == null || !basePath.isAbsolute()) {
            throw new ConfigurationException("Base path must be a valid absolute path");
        }

        try {
            Path normalizedRelativePath = Paths.get(relativePath).normalize();

            String pathStr = normalizedRelativePath.toString();

            if (pathStr.startsWith("../") || pathStr.equals("..") || pathStr.contains("/../")) {
                log.warn("Detected potential path traversal attempt: {}", relativePath);
                throw new ConfigurationException("Invalid path: contains path traversal patterns");
            }

            Path resolvedPath = basePath.resolve(normalizedRelativePath).normalize();

            if (!resolvedPath.startsWith(basePath)) {
                log.warn("Path escapes the base directory: {} (base: {})", resolvedPath, basePath);
                throw new ConfigurationException("Path escapes the allowed directory");
            }

            return resolvedPath;

        } catch (InvalidPathException e) {
            log.warn("Invalid path format: {}", relativePath, e);
            throw new ConfigurationException("Invalid path format: " + e.getMessage());
        }
    }

    /**
     * Checks if a given path is inside the specified base directory.
     *
     * @param path     The path to check
     * @param basePath The base directory path
     * @return true if the path is within the base directory, false otherwise
     */
    public boolean isPathWithinBase(Path path, Path basePath) {
        if (path == null || basePath == null) {
            return false;
        }

        Path normalizedPath = path.normalize();
        Path normalizedBasePath = basePath.normalize();

        return normalizedPath.startsWith(normalizedBasePath);
    }

    /**
     * Gets a relative path string from a complete path, relative to the base path.
     *
     * @param fullPath The complete path
     * @param basePath The base path to relativize against
     * @return A string representation of the relative path
     * @throws ConfigurationException If the full path is not within the base path
     */
    public String getRelativePath(Path fullPath, Path basePath) {
        if (!isPathWithinBase(fullPath, basePath)) {
            throw new ConfigurationException("Path is not within the base directory");
        }

        return basePath.relativize(fullPath).toString();
    }
}
