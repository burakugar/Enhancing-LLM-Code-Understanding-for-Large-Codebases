package com.localllm.assistant.parser.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Represents a semantically meaningful segment of code extracted by the parser.
 * Contains the code text, its type, location, metadata, and potentially its embedding.
 */
@Data // Lombok: Generates getters, setters, toString, equals, hashCode
@Builder // Lombok: Provides a builder pattern for object creation
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true) // Avoid issues with mutable fields/collections in equals/hashCode
public class CodeSegment {

    /**
     * Unique identifier for this code segment.
     * Recommended format: "{relativeFilePath}:{startLine}:{endLine}:{checksum|name}"
     * Example: "src/main/java/com/example/MyClass.java:25:40:processData"
     */
    @EqualsAndHashCode.Include // Use ID for equality checks
    private String id;

    /**
     * The raw text content of the code segment.
     */
    private String content;

    /**
     * The type of code segment (e.g., METHOD, CLASS).
     */
    private SegmentType type;

    /**
     * Relative path of the source file within the monitored codebase.
     * Example: "src/main/java/com/example/MyClass.java"
     */
    private String relativeFilePath;

    /**
     * Starting line number of the segment in the original file (1-based).
     */
    private int startLine;

    /**
     * Ending line number of the segment in the original file (1-based).
     */
    private int endLine;

    /**
     * The name of the primary entity represented by this segment, if applicable.
     * Example: Method name, Class name, Field name.
     */
    private String entityName;

    /**
     * Fully qualified name of the parent entity (e.g., class containing a method).
     * Example: "com.example.MyClass"
     */
    private String parentFqn;

    /**
     * Identifier of the parent CodeSegment (e.g., the ID of the class segment for a method segment).
     * Can be null for top-level segments like classes or files.
     */
    private String parentId;

    /**
     * List of identifiers of direct child CodeSegments (e.g., methods within a class).
     * Populated during parsing based on AST structure.
     */
    private List<String> childrenIds;

    /**
     * The timestamp of the last modification of the source file when this segment was parsed.
     * Used for incremental update checks.
     */
    private LocalDateTime fileLastModified;

    /**
     * Optional checksum (e.g., SHA-256) of the segment's content.
     * Can be used for more precise change detection during incremental updates.
     */
    private String contentChecksum;

    /**
     * Additional metadata extracted during parsing.
     * Examples: 'imports', 'annotations', 'modifiers', 'parameters', 'returnType'.
     * Flexible map to accommodate various details based on SegmentType.
     */
    private Map<String, Object> metadata;

    // Note: The vector embedding itself is typically stored separately or added later,
    // it's not usually part of the core CodeSegment model from the parser.

    /**
     * Default constructor for serialization.
     */
    public CodeSegment() {
    }

    /**
     * Creates a new code segment with all properties.
     */
    public CodeSegment(String id, String content, String filePath, int startLine, int endLine, 
                      String language, String type, String name) {
        this.id = id;
        this.content = content;
        this.relativeFilePath = filePath;
        this.startLine = startLine;
        this.endLine = endLine;
        this.entityName = name;
        this.type = SegmentType.valueOf(type);
        this.entityName = name;
    }

    // Getters and setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getRelativeFilePath() {
        return relativeFilePath;
    }

    public void setRelativeFilePath(String relativeFilePath) {
        this.relativeFilePath = relativeFilePath;
    }

    public int getStartLine() {
        return startLine;
    }

    public void setStartLine(int startLine) {
        this.startLine = startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public void setEndLine(int endLine) {
        this.endLine = endLine;
    }

    public String getEntityName() {
        return entityName;
    }

    public void setEntityName(String entityName) {
        this.entityName = entityName;
    }

    public String getParentFqn() {
        return parentFqn;
    }

    public void setParentFqn(String parentFqn) {
        this.parentFqn = parentFqn;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public List<String> getChildrenIds() {
        return childrenIds;
    }

    public void setChildrenIds(List<String> childrenIds) {
        this.childrenIds = childrenIds;
    }

    public LocalDateTime getFileLastModified() {
        return fileLastModified;
    }

    public void setFileLastModified(LocalDateTime fileLastModified) {
        this.fileLastModified = fileLastModified;
    }

    public String getContentChecksum() {
        return contentChecksum;
    }

    public void setContentChecksum(String contentChecksum) {
        this.contentChecksum = contentChecksum;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CodeSegment that = (CodeSegment) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "CodeSegment{" +
                "id='" + id + '\'' +
                ", relativeFilePath='" + relativeFilePath + '\'' +
                ", startLine=" + startLine +
                ", endLine=" + endLine +
                ", type='" + type + '\'' +
                ", entityName='" + entityName + '\'' +
                '}';
    }
} 
