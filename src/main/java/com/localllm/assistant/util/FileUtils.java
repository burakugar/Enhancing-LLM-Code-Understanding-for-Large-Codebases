package com.localllm.assistant.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        
        // Exclude common build and test directories
        return pathStr.contains("/target/") || 
               pathStr.contains("/build/") || 
               pathStr.contains("/.git/") ||
               pathStr.contains("/test/") ||  // Optionally exclude test files
               pathStr.contains("/.idea/") || 
               Files.isHidden(path);
    }
    
    /**
     * Gets the file extension from a path.
     * 
     * @param path The path to get the extension from
     * @return The file extension (without the dot) or an empty string if none
     */
    public static String getFileExtension(Path path) {
        String fileName = path.getFileName().toString();
        int lastDotIndex = fileName.lastIndexOf(".");
        return lastDotIndex > 0 ? fileName.substring(lastDotIndex + 1) : "";
    }
    
    /**
     * Checks if a path has a specific extension.
     * 
     * @param path The path to check
     * @param extension The extension to check for (without the dot)
     * @return true if the path has the specified extension, false otherwise
     */
    public static boolean hasExtension(Path path, String extension) {
        return getFileExtension(path).equalsIgnoreCase(extension);
    }
} 
