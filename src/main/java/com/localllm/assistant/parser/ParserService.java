package com.localllm.assistant.parser;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.localllm.assistant.parser.model.CodeSegment;

/**
 * Interface for the service responsible for parsing source code files
 * into structured CodeSegment objects using ANTLR.
 */
public interface ParserService {

    /**
     * Parses a single source code file asynchronously.
     *
     * @param filePath The absolute path to the source code file to parse.
     * @param basePath The absolute path to the root of the codebase (for calculating relative paths).
     * @return A CompletableFuture containing a List of CodeSegment objects extracted from the file.
     *         The future completes exceptionally with a ParsingException if parsing fails.
     */
    CompletableFuture<List<CodeSegment>> parseFileAsync(Path filePath, Path basePath);

    /**
     * Parses multiple source code files asynchronously.
     * This method can potentially optimize parsing by reusing parser instances or parallelizing.
     *
     * @param filePaths A List of absolute paths to the source code files to parse.
     * @param basePath The absolute path to the root of the codebase.
     * @return A CompletableFuture containing a List of all CodeSegment objects extracted from all files.
     *         The future completes exceptionally if any parsing task fails significantly.
     *         Individual file parsing errors should be handled internally and logged.
     */
    CompletableFuture<List<CodeSegment>> parseFilesAsync(List<Path> filePaths, Path basePath);
} 