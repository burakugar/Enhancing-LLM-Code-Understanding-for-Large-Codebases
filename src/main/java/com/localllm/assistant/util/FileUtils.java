package com.localllm.assistant.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Utility class for file operations.
 */
public class FileUtils {

    private static final Logger log = LoggerFactory.getLogger(FileUtils.class);

    /**
     * Recursively finds all Java files in the given directory.
     *
     * @param basePath The base directory to search in
     * @return A list of Path objects for all Java files found
     * @throws IOException If an I/O error occurs
     */
    public static List<Path> findAllJavaFiles(Path basePath) throws IOException {
        log.debug("Finding all Java files in {}", basePath);

        try (Stream<Path> pathStream = Files.walk(basePath)) {
            List<Path> javaFiles = pathStream
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> {
                    try {
                        return !isExcludedPath(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toList());

            log.debug("Found {} Java files in {}", javaFiles.size(), basePath);
            return javaFiles;
        }
    }

    /**
     * Checks if a path should be excluded from processing.
     * Excludes files in typical build, test, and hidden directories.
     *
     * @param path The path to check
     * @return true if the path should be excluded, false otherwise
     */
    private static boolean isExcludedPath(Path path) throws IOException {
        String pathStr = path.toString();

        return pathStr.contains("/target/") ||
            pathStr.contains("/build/") ||
            pathStr.contains("/.git/") ||
            pathStr.contains("/test/") ||
            pathStr.contains("/.idea/") ||
            Files.isHidden(path);
    }


}
