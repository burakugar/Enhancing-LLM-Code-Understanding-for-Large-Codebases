package com.localllm.assistant.parser.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.localllm.assistant.config.AsyncConfig;
import com.localllm.assistant.exception.ParsingException;
import com.localllm.assistant.parser.ParserService;
import com.localllm.assistant.parser.model.CodeSegment;
import com.localllm.assistant.parser.model.SegmentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Implementation of ParserService that uses ANTLR to parse Java source files
 * into CodeSegment objects.
 */
@Service
@RequiredArgsConstructor
public class AntlrParserServiceImpl implements ParserService {

    private static final Logger log = LoggerFactory.getLogger(AntlrParserServiceImpl.class);
    
    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_EMBEDDING)
    public CompletableFuture<List<CodeSegment>> parseFileAsync(Path filePath, Path basePath) {
        CompletableFuture<List<CodeSegment>> future = new CompletableFuture<>();
        
        try {
            if (!Files.exists(filePath)) {
                throw new ParsingException("File does not exist: " + filePath);
            }
            
            if (!filePath.toString().endsWith(".java")) {
                log.warn("File {} is not a Java file. Skipping parsing.", filePath);
                future.complete(Collections.emptyList());
                return future;
            }
            
            log.debug("Parsing file: {}", filePath);
            
            // Read file content
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            
            // Get relative path for storage
            String relativePath = basePath.relativize(filePath).toString().replace('\\', '/');
            
            // Get file modification time for caching/change detection
            LocalDateTime lastModified = LocalDateTime.now(); 
            try {
                lastModified = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(filePath).toInstant(),
                    TimeZone.getDefault().toZoneId()
                );
            } catch (IOException e) {
                log.warn("Could not get last modified time for {}: {}", filePath, e.getMessage());
            }
            
            // Parse the file into segments using ANTLR
            List<CodeSegment> segments = parseJavaContent(content, relativePath, lastModified);
            
            future.complete(segments);
            log.debug("Successfully parsed {} into {} code segments", relativePath, segments.size());
            
        } catch (IOException e) {
            String message = "Failed to read file: " + filePath;
            log.error(message, e);
            future.completeExceptionally(new ParsingException(message, e));
        } catch (Exception e) {
            String message = "Failed to parse file: " + filePath;
            log.error(message, e);
            future.completeExceptionally(new ParsingException(message, e));
        }
        
        return future;
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_EMBEDDING)
    public CompletableFuture<List<CodeSegment>> parseFilesAsync(List<Path> filePaths, Path basePath) {
        if (filePaths == null || filePaths.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        
        log.info("Starting parsing of {} files", filePaths.size());
        
        List<CompletableFuture<List<CodeSegment>>> futures = filePaths.stream()
                .map(filePath -> parseFileAsync(filePath, basePath))
                .collect(Collectors.toList());
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .flatMap(List::stream)
                        .collect(Collectors.toList())
                );
    }
    
    /**
     * Parse Java content into CodeSegment objects.
     * This implementation is a simplified version that extracts basic segments.
     * A full implementation would use the ANTLR grammar to extract detailed structural information.
     */
    private List<CodeSegment> parseJavaContent(String content, String relativePath, LocalDateTime lastModified) {
        List<CodeSegment> segments = new ArrayList<>();
        
        // For now, implement a simplified version that doesn't rely on full ANTLR parsing
        // In a real implementation, you would:
        // 1. Create a lexer from the content
        // 2. Create a parser from the lexer tokens
        // 3. Parse the compilation unit
        // 4. Walk the parse tree to identify segments
        
        // Add the entire file as a segment (simplistic approach)
        CodeSegment fileSegment = CodeSegment.builder()
                .id(relativePath + ":0:file")
                .content(content)
                .relativeFilePath(relativePath)
                .startLine(1)
                .endLine(countLines(content))
                .type(SegmentType.FILE)
                .entityName(getFileNameFromPath(relativePath))
                .fileLastModified(lastModified)
                .build();
        segments.add(fileSegment);
        
        // Extract package declaration if present
        extractPackageDeclaration(content, relativePath, lastModified).ifPresent(segments::add);
        
        // Extract simple class-level segments (basic approach)
        segments.addAll(extractSimpleClassSegments(content, relativePath, lastModified));
        
        // Extract method-level segments (basic approach)
        segments.addAll(extractSimpleMethodSegments(content, relativePath, lastModified));
        
        return segments;
    }
    
    /**
     * Extract the package declaration from the content.
     */
    private Optional<CodeSegment> extractPackageDeclaration(String content, String relativePath, LocalDateTime lastModified) {
        // Simple regex to match package declarations
        String packageRegex = "package\\s+([\\w.]+)\\s*;";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(packageRegex);
        java.util.regex.Matcher matcher = pattern.matcher(content);
        
        if (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            
            // Calculate line numbers (simplistic)
            int startLine = countLines(content.substring(0, start)) + 1;
            int endLine = countLines(content.substring(0, end));
            
            return Optional.of(CodeSegment.builder()
                    .id(relativePath + ":" + startLine + ":" + endLine + ":package")
                    .content(matcher.group(0))
                    .relativeFilePath(relativePath)
                    .startLine(startLine)
                    .endLine(endLine)
                    .type(SegmentType.PACKAGE_DECLARATION)
                    .entityName(matcher.group(1))
                    .fileLastModified(lastModified)
                    .build());
        }
        
        return Optional.empty();
    }
    
    /**
     * Extract class/interface/enum declarations from the content.
     * This is a simplified implementation and may not handle all edge cases.
     */
    private List<CodeSegment> extractSimpleClassSegments(String content, String relativePath, LocalDateTime lastModified) {
        List<CodeSegment> segments = new ArrayList<>();
        
        // Simple regex to match class/interface/enum declarations
        String classRegex = "(public|private|protected|)\\s*(class|interface|enum)\\s+(\\w+)";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(classRegex);
        java.util.regex.Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            int start = matcher.start();
            
            // Find the opening brace and matching closing brace
            int openBrace = content.indexOf('{', start);
            if (openBrace == -1) {
                continue;
            }
            
            // Find matching closing brace (simplistic implementation)
            int closeBrace = findClosingBrace(content, openBrace);
            if (closeBrace == -1) {
                continue;
            }
            
            String type = matcher.group(2).trim();
            String name = matcher.group(3).trim();
            String classContent = content.substring(start, closeBrace + 1);
            
            // Calculate line numbers
            int startLine = countLines(content.substring(0, start)) + 1;
            int endLine = countLines(content.substring(0, closeBrace + 1));
            
            SegmentType segmentType;
            switch (type) {
                case "class":
                    segmentType = SegmentType.CLASS;
                    break;
                case "interface":
                    segmentType = SegmentType.INTERFACE;
                    break;
                case "enum":
                    segmentType = SegmentType.ENUM;
                    break;
                default:
                    segmentType = SegmentType.UNKNOWN;
            }
            
            segments.add(CodeSegment.builder()
                    .id(relativePath + ":" + startLine + ":" + endLine + ":" + name)
                    .content(classContent)
                    .relativeFilePath(relativePath)
                    .startLine(startLine)
                    .endLine(endLine)
                    .type(segmentType)
                    .entityName(name)
                    .fileLastModified(lastModified)
                    .build());
        }
        
        return segments;
    }
    
    /**
     * Extract method declarations from the content.
     * This is a simplified implementation and may not handle all edge cases.
     */
    private List<CodeSegment> extractSimpleMethodSegments(String content, String relativePath, LocalDateTime lastModified) {
        List<CodeSegment> segments = new ArrayList<>();
        
        // Simple regex to match method declarations
        String methodRegex = "(public|private|protected|static|final|abstract|synchronized|\\s)*\\s+[\\w<>\\[\\],\\s]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*((throws\\s+[\\w.,\\s]+)\\s*)?\\{";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(methodRegex);
        java.util.regex.Matcher matcher = pattern.matcher(content);
        
        while (matcher.find()) {
            int start = matcher.start();
            int openBrace = content.indexOf('{', start);
            if (openBrace == -1) {
                continue;
            }
            
            // Find matching closing brace
            int closeBrace = findClosingBrace(content, openBrace);
            if (closeBrace == -1) {
                continue;
            }
            
            String name = matcher.group(2).trim();
            String methodContent = content.substring(start, closeBrace + 1);
            
            // Calculate line numbers
            int startLine = countLines(content.substring(0, start)) + 1;
            int endLine = countLines(content.substring(0, closeBrace + 1));
            
            // Determine if this is a constructor (simplified check)
            boolean isConstructor = Character.isUpperCase(name.charAt(0)) && 
                                   content.substring(Math.max(0, start - 20), start).contains("class " + name);
            
            segments.add(CodeSegment.builder()
                    .id(relativePath + ":" + startLine + ":" + endLine + ":" + name)
                    .content(methodContent)
                    .relativeFilePath(relativePath)
                    .startLine(startLine)
                    .endLine(endLine)
                    .type(isConstructor ? SegmentType.CONSTRUCTOR : SegmentType.METHOD)
                    .entityName(name)
                    .fileLastModified(lastModified)
                    .build());
        }
        
        return segments;
    }
    
    /**
     * Find the matching closing brace for an opening brace at the given position.
     */
    private int findClosingBrace(String content, int openBracePos) {
        int level = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        
        for (int i = openBracePos; i < content.length(); i++) {
            char c = content.charAt(i);
            char next = (i + 1 < content.length()) ? content.charAt(i + 1) : '\0';
            
            // Skip comments and strings
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (!inString && !inChar && c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (!inString && !inChar && c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            
            // Handle strings and character literals
            if (!inChar && c == '"' && (i == 0 || content.charAt(i - 1) != '\\')) {
                inString = !inString;
                continue;
            }
            if (!inString && c == '\'' && (i == 0 || content.charAt(i - 1) != '\\')) {
                inChar = !inChar;
                continue;
            }
            
            // Skip if in string or char
            if (inString || inChar) {
                continue;
            }
            
            // Count braces
            if (c == '{') {
                level++;
            } else if (c == '}') {
                level--;
                if (level == 0) {
                    return i;
                }
            }
        }
        
        return -1; // No matching closing brace found
    }
    
    /**
     * Count the number of lines in a string.
     */
    private int countLines(String str) {
        int count = 1;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }
    
    /**
     * Extract the file name from a path.
     */
    private String getFileNameFromPath(String path) {
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }
} 
