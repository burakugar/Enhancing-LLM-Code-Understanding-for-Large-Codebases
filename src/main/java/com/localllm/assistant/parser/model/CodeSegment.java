package com.localllm.assistant.parser.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class CodeSegment {

    @EqualsAndHashCode.Include
    private String id;
    private String content;
    private SegmentType type;
    private String relativeFilePath;
    private int startLine;
    private int endLine;
    private String entityName;
    private String parentFqn;
    private String parentId;
    private List<String> childrenIds;
    private LocalDateTime fileLastModified;
    private String contentChecksum;
    private Map<String, Object> metadata;

    private boolean isSubChunk = false;
    private String originalSegmentId;
    private int chunkNumber = 0;

    @Override
    public String toString() {
        return "CodeSegment{" +
            "id='" + id + '\'' +
            ", type=" + type +
            (isSubChunk ? ", originalSegmentId='" + originalSegmentId + '\'' + ", chunkNumber=" + chunkNumber : "") +
            ", entityName='" + entityName + '\'' +
            ", relativeFilePath='" + relativeFilePath + '\'' +
            ", startLine=" + startLine +
            ", endLine=" + endLine +
            '}';
    }
}
