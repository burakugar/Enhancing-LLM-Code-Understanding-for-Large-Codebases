package com.localllm.assistant.parser.impl;

import com.localllm.assistant.parser.model.CodeSegment;
import com.localllm.assistant.parser.model.SegmentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaParserServiceImplTest {

    private JavaParserServiceImpl parserService;

    @Mock
    private Executor mockExecutor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        Executor immediateExecutor = Runnable::run;
        parserService = new JavaParserServiceImpl(immediateExecutor);
        setMaxSegmentCharLength(2000);
        setSubChunkOverlapChars(100);
    }

    private void setMaxSegmentCharLength(int value) {
        try {
            var field = JavaParserServiceImpl.class.getDeclaredField("maxSegmentCharLength");
            field.setAccessible(true);
            field.set(parserService, value);
        } catch (Exception e) {
        }
    }

    private void setSubChunkOverlapChars(int value) {
        try {
            var field = JavaParserServiceImpl.class.getDeclaredField("subChunkOverlapChars");
            field.setAccessible(true);
            field.set(parserService, value);
        } catch (Exception e) {
        }
    }

    private Path createTestFile(Path dir, String fileName, String content) throws IOException {
        Path filePath = dir.resolve(fileName);
        Files.writeString(filePath, content);
        return filePath;
    }

    private static final String SIMPLE_CLASS_CONTENT =
        "package com.example.test;\n" +
            "\n" +
            "import java.util.List;\n" +
            "import java.util.Map;\n" +
            "\n" +
            "@Deprecated\n" +
            "public class SimpleClass {\n" +
            "    private int count = 0;\n" +
            "    \n" +
            "    static {\n" +
            "        // Static initializer\n" +
            "    }\n" +
            "    \n" +
            "    {\n" +
            "        // Instance initializer\n" +
            "    }\n" +
            "    \n" +
            "    public String getMessage() {\n" +
            "        return \"Hello\";\n" +
            "    }\n" +
            "    \n" +
            "    public static class NestedStaticClass {\n" +
            "    }\n" +
            "    \n" +
            "    public class InnerClass {\n" +
            "    }\n" +
            "}\n" +
            "\n" +
            "interface SimpleInterface {\n" +
            "    void doSomething();\n" +
            "}\n" +
            "\n" +
            "enum SimpleEnum {\n" +
            "    A, B, C\n" +
            "}\n" +
            "\n" +
            "@interface SimpleAnnotation {\n" +
            "    String value() default \"\";\n" +
            "}";

    private static final String MODULE_INFO_CONTENT =
        "@Deprecated\n" +
            "module com.example.mymodule {\n" +
            "    requires java.sql;\n" +
            "    requires static com.example.othermodule;\n" +
            "    requires transitive java.logging;\n" +
            "\n" +
            "    exports com.example.mymodule.api;\n" +
            "    exports com.example.mymodule.internal to com.friend.module, com.another.friend.module;\n" +
            "\n" +
            "    opens com.example.mymodule.spi;\n" +
            "    opens com.example.mymodule.reflect to com.another.friend;\n" +
            "\n" +
            "    uses com.example.mymodule.api.MyService;\n" +
            "    provides com.example.mymodule.api.MyService with com.example.mymodule.impl.MyServiceImpl1, com.example.mymodule.impl.MyServiceImpl2;\n" +
            "}";

    private static final String MULTIPLE_FIELDS_CONTENT =
        "package com.example.test;\n" +
            "\n" +
            "public class MultipleFieldsInDeclaration {\n" +
            "    @SuppressWarnings(\"unused\")\n" +
            "    private int alpha, beta;\n" +
            "    \n" +
            "    public String gamma, delta;\n" +
            "}";

    @Test
    void testParseSimpleClass(@TempDir Path tempDir) throws Exception {
        Path testFilePath = createTestFile(tempDir, "SimpleClass.java", SIMPLE_CLASS_CONTENT);
        Path basePath = tempDir;

        List<CodeSegment> segments = parserService.parseFileAsync(testFilePath, basePath).join();

        assertNotNull(segments);

        System.out.println("Found segments:");
        segments.forEach(s -> System.out.println(s.getType() + ":" + s.getEntityName() + " (L" + s.getStartLine() + ")"));

        assertTrue(segments.size() >= 10, "Should find at least 10 segments. Found: " + segments.size());

        CodeSegment classSegment = findSegmentByTypeAndName(segments, SegmentType.CLASS, "SimpleClass").orElse(null);
        assertNotNull(classSegment, "Class segment SimpleClass not found");
        assertEquals("com.example.test.SimpleClass", classSegment.getMetadata().get("fqn"));
        Map<String, Object> classMeta = classSegment.getMetadata();
        assertTrue(((String) classMeta.get("modifiers")).contains("PUBLIC"));
        List<Map<String, String>> classAnnotations = (List<Map<String, String>>) classMeta.get("annotations");
        assertNotNull(classAnnotations, "Annotations list for SimpleClass should not be null");
        assertTrue(classAnnotations.stream().anyMatch(a -> "Deprecated".equals(a.get("name"))), "SimpleClass should have @Deprecated annotation");

        CodeSegment fieldSegment = findSegmentByTypeAndName(segments, SegmentType.FIELD, "count").orElse(null);
        assertNotNull(fieldSegment, "Field segment 'count' not found");
        assertEquals(classSegment.getId(), fieldSegment.getParentId());
        assertEquals("com.example.test.SimpleClass#count", fieldSegment.getMetadata().get("fqn"));
        assertEquals("int", fieldSegment.getMetadata().get("fieldType"));

        CodeSegment methodSegment = findSegmentByTypeAndName(segments, SegmentType.METHOD, "getMessage").orElse(null);
        assertNotNull(methodSegment, "Method segment 'getMessage' not found");
        assertEquals(classSegment.getId(), methodSegment.getParentId());
        assertEquals("com.example.test.SimpleClass#getMessage", methodSegment.getMetadata().get("fqn"));

        CodeSegment staticBlockSegment = findSegmentByTypeAndName(segments, SegmentType.STATIC_BLOCK, "static_initializer").orElse(null);
        assertNotNull(staticBlockSegment, "Static block segment not found");
        assertEquals(classSegment.getId(), staticBlockSegment.getParentId());

        CodeSegment instanceBlockSegment = findSegmentByTypeAndName(segments, SegmentType.INSTANCE_BLOCK, "instance_initializer").orElse(null);
        assertNotNull(instanceBlockSegment, "Instance block segment not found");
        assertEquals(classSegment.getId(), instanceBlockSegment.getParentId());

        CodeSegment nestedStaticClassSegment = findSegmentByTypeAndName(segments, SegmentType.CLASS, "NestedStaticClass").orElse(null);
        assertNotNull(nestedStaticClassSegment, "NestedStaticClass segment not found");
        assertEquals(classSegment.getId(), nestedStaticClassSegment.getParentId());
        assertEquals("com.example.test.SimpleClass.NestedStaticClass", nestedStaticClassSegment.getMetadata().get("fqn"));

        CodeSegment innerClassSegment = findSegmentByTypeAndName(segments, SegmentType.CLASS, "InnerClass").orElse(null);
        assertNotNull(innerClassSegment, "InnerClass segment not found");
        assertEquals(classSegment.getId(), innerClassSegment.getParentId());
        assertEquals("com.example.test.SimpleClass.InnerClass", innerClassSegment.getMetadata().get("fqn"));

        CodeSegment interfaceSegment = findSegmentByTypeAndName(segments, SegmentType.INTERFACE, "SimpleInterface").orElse(null);
        assertNotNull(interfaceSegment, "Interface SimpleInterface not found");
        assertNull(interfaceSegment.getParentId(), "SimpleInterface should be top-level");
        assertEquals("com.example.test.SimpleInterface", interfaceSegment.getMetadata().get("fqn"));

        CodeSegment enumSegment = findSegmentByTypeAndName(segments, SegmentType.ENUM, "SimpleEnum").orElse(null);
        assertNotNull(enumSegment, "Enum SimpleEnum not found");
        assertNull(enumSegment.getParentId(), "SimpleEnum should be top-level");
        assertEquals("com.example.test.SimpleEnum", enumSegment.getMetadata().get("fqn"));

        CodeSegment enumConstASegment = findSegmentByTypeAndName(segments, SegmentType.FIELD, "A").orElse(null);
        assertNotNull(enumConstASegment, "Enum constant A not found");
        assertEquals(enumSegment.getId(), enumConstASegment.getParentId());
        assertEquals("com.example.test.SimpleEnum#A", enumConstASegment.getMetadata().get("fqn"));
        assertEquals("SimpleEnum", enumConstASegment.getMetadata().get("fieldType"));

        CodeSegment annotationSegment = findSegmentByTypeAndName(segments, SegmentType.ANNOTATION, "SimpleAnnotation").orElse(null);
        assertNotNull(annotationSegment, "Annotation SimpleAnnotation not found");
        assertNull(annotationSegment.getParentId(), "SimpleAnnotation should be top-level");
        assertEquals("com.example.test.SimpleAnnotation", annotationSegment.getMetadata().get("fqn"));
    }

    @Test
    void testParseModuleInfoFile(@TempDir Path tempDir) throws Exception {
        Path testFilePath = createTestFile(tempDir, "module-info.java", MODULE_INFO_CONTENT);
        Path basePath = tempDir;

        List<CodeSegment> segments = parserService.parseFileAsync(testFilePath, basePath).join();

        assertNotNull(segments);

        assertEquals(1, segments.size(), "Should find 1 segment (module declaration only). Segments: \n" +
            segments.stream().map(s -> s.getType() + ":" + s.getEntityName() + " (FQN: " + s.getMetadata().get("fqn") + ")")
                .collect(Collectors.joining("\n")));

        CodeSegment moduleSegment = findSegmentByTypeAndName(segments, SegmentType.MODULE_DECLARATION, "com.example.mymodule").orElse(null);
        assertNotNull(moduleSegment, "Module declaration com.example.mymodule not found");
        assertEquals("com.example.mymodule", moduleSegment.getMetadata().get("fqn"));
        assertNull(moduleSegment.getParentId());
    }

    @Test
    void testParseEmptyFile(@TempDir Path tempDir) throws Exception {
        Path testFilePath = createTestFile(tempDir, "EmptyFile.java", "");
        Path basePath = tempDir;

        List<CodeSegment> segments = parserService.parseFileAsync(testFilePath, basePath).join();

        assertNotNull(segments, "Segments list should not be null for an empty file.");
        assertTrue(segments.isEmpty(), "Parsing an empty file should result in zero segments from our visitor.");
    }

    @Test
    void testParseCommentsOnlyFile(@TempDir Path tempDir) throws Exception {
        Path testFilePath = createTestFile(tempDir, "CommentsOnlyFile.java", "// Just a comment\n/* Another comment */");
        Path basePath = tempDir;

        List<CodeSegment> segments = parserService.parseFileAsync(testFilePath, basePath).join();

        assertNotNull(segments, "Segments list should not be null for a comments-only file.");
        assertTrue(segments.isEmpty(), "Parsing a comments-only file should result in zero segments from our visitor.");
    }

    @Test
    void testParseMultipleFieldsInDeclaration(@TempDir Path tempDir) throws Exception {
        Path testFilePath = createTestFile(tempDir, "MultipleFieldsInDeclaration.java", MULTIPLE_FIELDS_CONTENT);
        Path basePath = tempDir;

        List<CodeSegment> segments = parserService.parseFileAsync(testFilePath, basePath).join();
        assertNotNull(segments);

        assertEquals(5, segments.size(), "Should find 5 segments.");

        CodeSegment classSegment = findSegmentByTypeAndName(segments, SegmentType.CLASS, "MultipleFieldsInDeclaration").orElse(null);
        assertNotNull(classSegment, "Class MultipleFieldsInDeclaration not found");
        String classFqn = "com.example.test.MultipleFieldsInDeclaration";
        assertEquals(classFqn, classSegment.getMetadata().get("fqn"));

        CodeSegment alphaSegment = findSegmentByTypeAndName(segments, SegmentType.FIELD, "alpha").orElse(null);
        assertNotNull(alphaSegment, "Field 'alpha' not found");
        assertEquals(classSegment.getId(), alphaSegment.getParentId());
        assertEquals(classFqn + "#alpha", alphaSegment.getMetadata().get("fqn"));
        assertEquals("int", alphaSegment.getMetadata().get("fieldType"));
        List<Map<String, String>> alphaAnnotations = (List<Map<String, String>>) alphaSegment.getMetadata().get("annotations");
        assertNotNull(alphaAnnotations);
        assertTrue(alphaAnnotations.stream().anyMatch(ann -> "SuppressWarnings".equals(ann.get("name"))), "Alpha should have @SuppressWarnings");

        CodeSegment betaSegment = findSegmentByTypeAndName(segments, SegmentType.FIELD, "beta").orElse(null);
        assertNotNull(betaSegment, "Field 'beta' not found");
        assertEquals(classSegment.getId(), betaSegment.getParentId());
        assertEquals(classFqn + "#beta", betaSegment.getMetadata().get("fqn"));
        assertEquals("int", betaSegment.getMetadata().get("fieldType"));
    }

    @Test
    void testParseFileWithLargeContent(@TempDir Path tempDir) throws Exception {
        StringBuilder largeContent = new StringBuilder();
        largeContent.append("package com.example.test;\n\n");
        largeContent.append("public class LargeClass {\n");
        largeContent.append("    public void largeMethod() {\n");

        for (int i = 0; i < 100; i++) {
            largeContent.append("        System.out.println(\"This is line ").append(i).append(" of a very long method\");\n");
        }

        largeContent.append("    }\n");
        largeContent.append("}\n");

        Path testFilePath = createTestFile(tempDir, "LargeClass.java", largeContent.toString());
        Path basePath = tempDir;

        List<CodeSegment> segments = parserService.parseFileAsync(testFilePath, basePath).join();

        assertNotNull(segments);
        assertTrue(segments.size() >= 2, "Should find at least 2 segments (class and method)");
        boolean hasSubChunks = segments.stream().anyMatch(CodeSegment::isSubChunk);
        if (largeContent.length() > 2000) {
            assertTrue(hasSubChunks, "Large content should be chunked into sub-segments");
        }
    }

    private Optional<CodeSegment> findSegmentByTypeAndName(List<CodeSegment> segments, SegmentType type, String name) {
        return segments.stream()
            .filter(s -> s.getType() == type && name.equals(s.getEntityName()))
            .findFirst();
    }

}
