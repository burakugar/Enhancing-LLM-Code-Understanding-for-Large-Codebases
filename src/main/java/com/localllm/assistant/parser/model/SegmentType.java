package com.localllm.assistant.parser.model;

/**
 * Enumeration defining the types of code segments identified during parsing.
 * This helps categorize segments for storage, retrieval, and context construction.
 */
public enum SegmentType {
    FILE,
    PACKAGE_DECLARATION,
    IMPORT_DECLARATION,
    CLASS,            // Class definition
    INTERFACE,        // Interface definition
    ENUM,             // Enum definition
    ANNOTATION,       // Annotation definition (@interface)
    METHOD,           // Regular method
    CONSTRUCTOR,      // Constructor method
    FIELD,            // Class or instance variable
    STATIC_BLOCK,     // Static initializer block
    INSTANCE_BLOCK,   // Instance initializer block
    BLOCK_COMMENT,    // Multi-line comment block (if configured to parse)
    LINE_COMMENT,     // Single-line comment (if configured to parse)
    JAVADOC_COMMENT,  // Javadoc comment block (if configured to parse),
    MODULE_DECLARATION,
    MODULE_DIRECTIVE_REQUIRES,
    MODULE_DIRECTIVE_EXPORTS,
    MODULE_DIRECTIVE_OPENS,
    MODULE_DIRECTIVE_USES,
    MODULE_DIRECTIVE_PROVIDES,
    UNKNOWN
}
