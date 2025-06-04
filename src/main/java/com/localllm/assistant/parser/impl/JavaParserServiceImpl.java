    package com.localllm.assistant.parser.impl;

    import com.github.javaparser.ParseProblemException;
    import com.github.javaparser.StaticJavaParser;
    import com.github.javaparser.ast.CompilationUnit;
    import com.github.javaparser.ast.ImportDeclaration;
    import com.github.javaparser.ast.Modifier;
    import com.github.javaparser.ast.Node;
    import com.github.javaparser.ast.PackageDeclaration;
    import com.github.javaparser.ast.body.AnnotationDeclaration;
    import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
    import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
    import com.github.javaparser.ast.body.ConstructorDeclaration;
    import com.github.javaparser.ast.body.EnumConstantDeclaration;
    import com.github.javaparser.ast.body.EnumDeclaration;
    import com.github.javaparser.ast.body.FieldDeclaration;
    import com.github.javaparser.ast.body.InitializerDeclaration;
    import com.github.javaparser.ast.body.MethodDeclaration;
    import com.github.javaparser.ast.body.VariableDeclarator;
    import com.github.javaparser.ast.expr.AnnotationExpr;
    import com.github.javaparser.ast.modules.ModuleDeclaration;
    import com.github.javaparser.ast.modules.ModuleExportsDirective;
    import com.github.javaparser.ast.modules.ModuleOpensDirective;
    import com.github.javaparser.ast.modules.ModuleProvidesDirective;
    import com.github.javaparser.ast.modules.ModuleRequiresDirective;
    import com.github.javaparser.ast.modules.ModuleUsesDirective;
    import com.github.javaparser.ast.type.ClassOrInterfaceType;
    import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
    import com.localllm.assistant.config.AsyncConfig;
    import com.localllm.assistant.exception.ParsingException;
    import com.localllm.assistant.parser.ParserService;
    import com.localllm.assistant.parser.model.CodeSegment;
    import com.localllm.assistant.parser.model.SegmentType;
    import org.slf4j.Logger;
    import org.slf4j.LoggerFactory;
    import org.springframework.beans.factory.annotation.Qualifier;
    import org.springframework.beans.factory.annotation.Value;
    import org.springframework.scheduling.annotation.Async;
    import org.springframework.stereotype.Service;

    import java.io.IOException;
    import java.nio.charset.StandardCharsets;
    import java.nio.file.Files;
    import java.nio.file.Path;
    import java.security.MessageDigest;
    import java.security.NoSuchAlgorithmException;
    import java.time.LocalDateTime;
    import java.time.ZoneId;
    import java.util.ArrayList;
    import java.util.Collections;
    import java.util.HashMap;
    import java.util.HexFormat;
    import java.util.List;
    import java.util.Map;
    import java.util.Optional;
    import java.util.Stack;
    import java.util.concurrent.CompletableFuture;
    import java.util.concurrent.Executor;
    import java.util.stream.Collectors;

    @Service
    public class JavaParserServiceImpl implements ParserService {

        private static final Logger log = LoggerFactory.getLogger(JavaParserServiceImpl.class);
        private static final MessageDigest SHA256_DIGEST;
        private final Executor parsingExecutor;

        @Value("${parser.maxSegmentCharLength:2000}")
        private int maxSegmentCharLength;

        @Value("${parser.subChunkOverlapChars:100}")
        private int subChunkOverlapChars;

        static {
            try {
                SHA256_DIGEST = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("Could not initialize SHA-256 digest", e);
            }
        }

        private boolean shouldProcessFile(Path file) {
            String fileName = file.getFileName().toString();

            if (fileName.contains("Test") ||
                fileName.contains("test") ||
                fileName.startsWith("Generated") ||
                file.toString().contains("/target/") ||
                file.toString().contains("/build/") ||
                file.toString().contains("/.git/") ||
                file.toString().contains("/.idea/") ||
                file.toString().contains("/node_modules/")) {
                return false;
            }

            try {
                if (Files.size(file) > 1024 * 1024) {
                    log.debug("Skipping large file: {}", fileName);
                    return false;
                }

                List<String> firstLines = Files.readAllLines(file).stream()
                    .limit(50)
                    .collect(Collectors.toList());

                String preview = String.join("\n", firstLines);

                if (preview.contains("record ") ||
                    preview.contains("sealed ") ||
                    preview.contains("permits ") ||
                    preview.contains("yield ") ||
                    preview.contains("switch (") && preview.contains("->") ||
                    preview.matches(".*\\b_\\s*[,;)].*")) {
                    log.debug("Skipping file with modern Java features: {}", fileName);
                    return false;
                }

            } catch (Exception e) {
                log.debug("Could not pre-check file: {}, will attempt to parse", fileName);
            }

            return fileName.endsWith(".java") ||
                fileName.endsWith(".kt") ||
                fileName.endsWith(".scala");
        }

        public JavaParserServiceImpl(@Qualifier(AsyncConfig.TASK_EXECUTOR_PARSING) Executor parsingExecutor) {
            this.parsingExecutor = parsingExecutor;
        }

        @Override
        @Async(AsyncConfig.TASK_EXECUTOR_PARSING)
        public CompletableFuture<List<CodeSegment>> parseFileAsync(Path filePath, Path basePath) {
            log.debug("Parsing file: {} on thread: {}", filePath, Thread.currentThread().getName());
            return CompletableFuture.supplyAsync(() -> {
                try {
                    if (!Files.exists(filePath)) {
                        log.warn("File does not exist, skipping: {}", filePath);
                        return Collections.<CodeSegment>emptyList();
                    }

                    if (!shouldProcessFile(filePath)) {
                        log.debug("Skipping file based on filter: {}", filePath);
                        return Collections.<CodeSegment>emptyList();
                    }

                    String relativePath = basePath.relativize(filePath).toString().replace('\\', '/');
                    LocalDateTime lastModified = readFileLastModified(filePath);
                    CompilationUnit cu = StaticJavaParser.parse(filePath);
                    List<CodeSegment> segments = new ArrayList<>();
                    SegmentVisitor visitor = new SegmentVisitor(relativePath, lastModified, maxSegmentCharLength, subChunkOverlapChars);
                    visitor.visit(cu, segments);
                    log.info("Successfully parsed file: {} into {} segments (including sub-chunks) using JavaParser", relativePath, segments.size());
                    return segments;

                } catch (IOException e) {
                    String relativePath = basePath.relativize(filePath).toString().replace('\\', '/');
                    log.warn("Skipping file due to IO error: {} - {}", relativePath, e.getMessage());
                    return Collections.<CodeSegment>emptyList();

                } catch (ParseProblemException e) {
                    String relativePath = basePath.relativize(filePath).toString().replace('\\', '/');
                    log.warn("Skipping file due to parse errors: {} - Problems: {}",
                        relativePath, e.getProblems().stream()
                            .map(p -> p.toString())
                            .limit(3)
                            .collect(Collectors.joining("; ")));
                    return Collections.<CodeSegment>emptyList();
                } catch (Exception e) {
                    String relativePath = basePath.relativize(filePath).toString().replace('\\', '/');
                    log.warn("Skipping file due to unexpected error: {} - {}", relativePath, e.getMessage());
                    return Collections.<CodeSegment>emptyList();
                }
            }, parsingExecutor);
        }


        @Override
        public CompletableFuture<List<CodeSegment>> parseFilesAsync(List<Path> filePaths, Path basePath) {
            if (filePaths == null || filePaths.isEmpty()) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }

            log.info("Queueing parsing for {} files.", filePaths.size());

            int batchSize = 50;
            List<CompletableFuture<List<CodeSegment>>> batchFutures = new ArrayList<>();

            for (int i = 0; i < filePaths.size(); i += batchSize) {
                int endIndex = Math.min(i + batchSize, filePaths.size());
                List<Path> batch = filePaths.subList(i, endIndex);

                CompletableFuture<List<CodeSegment>> batchFuture = processBatch(batch, basePath, i / batchSize + 1);
                batchFutures.add(batchFuture);
            }

            return CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
                .thenApplyAsync(v -> {
                    log.info("Aggregating parsing results for {} batches.", batchFutures.size());
                    return batchFutures.stream()
                        .map(CompletableFuture::join)
                        .flatMap(List::stream)
                        .collect(Collectors.toList());
                }, parsingExecutor)
                .exceptionally(ex -> {
                    log.error("Error occurred during batch file parsing completion stage.", ex);
                    throw new ParsingException("Batch parsing failed at completion stage", ex);
                });
        }

        private CompletableFuture<List<CodeSegment>> processBatch(List<Path> batch, Path basePath, int batchNumber) {
            return CompletableFuture.supplyAsync(() -> {
                log.info("Processing batch {} with {} files", batchNumber, batch.size());

                List<CompletableFuture<List<CodeSegment>>> futures = batch.stream()
                    .map(filePath -> parseFileAsync(filePath, basePath))
                    .collect(Collectors.toList());

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                List<CodeSegment> batchResults = futures.stream()
                    .map(future -> {
                        try {
                            return future.join();
                        } catch (Exception e) {
                            Throwable cause = e.getCause() != null ? e.getCause() : e;
                            log.error("A file parsing task failed in batch {}: {}", batchNumber, cause.getMessage(),
                                cause instanceof ParsingException ? null : cause);
                            return Collections.<CodeSegment>emptyList();
                        }
                    })
                    .flatMap(List::stream)
                    .collect(Collectors.toList());

                log.info("Completed batch {} with {} segments", batchNumber, batchResults.size());
                return batchResults;

            }, parsingExecutor);
        }

        private LocalDateTime readFileLastModified(Path filePath) {
            try {
                return LocalDateTime.ofInstant(Files.getLastModifiedTime(filePath).toInstant(), ZoneId.systemDefault());
            } catch (IOException e) {
                log.warn("Could not read last modified time for file: {}, using current time.", filePath, e);
                return LocalDateTime.now();
            }
        }

        private static String calculateChecksum(String text) {
            if (text == null) {
                return "";
            }
            synchronized (SHA256_DIGEST) {
                SHA256_DIGEST.reset();
                byte[] hash = SHA256_DIGEST.digest(text.getBytes(StandardCharsets.UTF_8));
                String checksumFull = HexFormat.of().formatHex(hash);
                return checksumFull.length() >= 8 ? checksumFull.substring(0, 8) : checksumFull;
            }
        }

        private static class SegmentVisitor extends VoidVisitorAdapter<List<CodeSegment>> {
            private static final Logger log = LoggerFactory.getLogger(SegmentVisitor.class);
            private final String relativeFilePath;
            private final LocalDateTime fileLastModified;
            private final Stack<String> fqnStack = new Stack<>();
            private final Stack<String> idStack = new Stack<>();
            private final Map<String, String> currentImports = new HashMap<>();
            private String currentPackage = "";
            private final int maxSegmentCharLength;
            private final int subChunkOverlapChars;

            public SegmentVisitor(String relativeFilePath, LocalDateTime fileLastModified, int maxSegmentCharLength, int subChunkOverlapChars) {
                this.relativeFilePath = relativeFilePath;
                this.fileLastModified = fileLastModified;
                this.maxSegmentCharLength = maxSegmentCharLength;
                this.subChunkOverlapChars = subChunkOverlapChars;
            }

            private int getLine(Optional<com.github.javaparser.Position> position) {
                return position.map(p -> p.line).orElse(-1);
            }

            private String generateBaseId(Node node, SegmentType type, String entityName) {
                int startLine = getLine(node.getBegin());
                String namePart = (entityName != null && !entityName.isEmpty()) ? entityName.replaceAll("[^a-zA-Z0-9._-]", "_") : "anonymous";
                return relativeFilePath + ":" + startLine + ":" + type.name() + ":" + namePart;
            }

            private String finalizeId(String baseId, String content) {
                String checksumFull = calculateChecksum(content);
                String checksumPart = checksumFull.length() >= 8 ? checksumFull.substring(0, 8) : checksumFull;
                return baseId + ":" + checksumPart;
            }

            private String calculateFqn(SegmentType type, String entityName, String parentFqn) {
                boolean hasParent = parentFqn != null && !parentFqn.isEmpty();
                String name = (entityName != null) ? entityName : "";
                switch (type) {
                    case PACKAGE_DECLARATION:
                        return name;
                    case IMPORT_DECLARATION:
                        return name;
                    case CLASS:
                    case INTERFACE:
                    case ENUM:
                    case ANNOTATION:
                        if (!hasParent && !currentPackage.isEmpty()) {
                            return currentPackage + "." + name;
                        }
                        return hasParent ? parentFqn + "." + name : name;
                    case METHOD:
                    case CONSTRUCTOR:
                    case FIELD:
                        return hasParent ? parentFqn + "#" + name : name;
                    case STATIC_BLOCK:
                        return hasParent ? parentFqn + "#static_initializer" : "static_initializer";
                    case INSTANCE_BLOCK:
                        return hasParent ? parentFqn + "#instance_initializer" : "instance_initializer";
                    case MODULE_DECLARATION:
                        return name;
                    case MODULE_DIRECTIVE_REQUIRES:
                        return hasParent ? parentFqn + "#requires:" + name : "requires:" + name;
                    case MODULE_DIRECTIVE_EXPORTS:
                        return hasParent ? parentFqn + "#exports:" + name : "exports:" + name;
                    case MODULE_DIRECTIVE_OPENS:
                        return hasParent ? parentFqn + "#opens:" + name : "opens:" + name;
                    case MODULE_DIRECTIVE_USES:
                        return hasParent ? parentFqn + "#uses:" + name : "uses:" + name;
                    case MODULE_DIRECTIVE_PROVIDES:
                        return hasParent ? parentFqn + "#provides:" + name : "provides:" + name;
                    default:
                        return hasParent ? parentFqn + "." + name : name;
                }
            }

            private void addSegment(Node node, SegmentType type, String entityName, Map<String, Object> extraMetadata, List<CodeSegment> collector) {
                Optional<com.github.javaparser.Position> beginPos = node.getBegin();
                Optional<com.github.javaparser.Position> endPos = node.getEnd();

                if (beginPos.isEmpty() || endPos.isEmpty()) {
                    log.warn("Skipping segment due to missing position information: Type={}, Name={}", type, entityName);
                    return;
                }

                String originalContent = node.toString();
                int originalStartLine = beginPos.get().line;
                int originalEndLine = endPos.get().line;
                String baseSegmentId = generateBaseId(node, type, entityName);

                String parentId = idStack.isEmpty() ? null : idStack.peek();
                String parentFqnForCalc = fqnStack.isEmpty() ? (type == SegmentType.MODULE_DECLARATION ? "" : currentPackage) : fqnStack.peek();
                String fqn = calculateFqn(type, entityName, parentFqnForCalc);

                Map<String, Object> baseMetadata = new HashMap<>();
                baseMetadata.put("fqn", fqn);
                if (type != SegmentType.MODULE_DECLARATION && !type.name().startsWith("MODULE_DIRECTIVE")) {
                    baseMetadata.put("imports", new HashMap<>(currentImports));
                }
                if (extraMetadata != null) {
                    baseMetadata.putAll(extraMetadata);
                }

                if (originalContent.length() > maxSegmentCharLength) {
                    log.debug("Segment '{}' (type: {}, length: {}) exceeds max length {}, chunking...",
                        entityName, type, originalContent.length(), maxSegmentCharLength);
                    List<String> chunks = splitContentIntoChunks(originalContent, maxSegmentCharLength, subChunkOverlapChars);
                    for (int i = 0; i < chunks.size(); i++) {
                        String chunkContent = chunks.get(i);
                        String chunkId = finalizeId(baseSegmentId + "_chunk" + i, chunkContent);
                        Map<String, Object> chunkMetadata = new HashMap<>(baseMetadata);
                        chunkMetadata.put("originalSegmentId", finalizeId(baseSegmentId, originalContent));
                        chunkMetadata.put("chunkNumber", i);
                        chunkMetadata.put("totalChunks", chunks.size());

                        CodeSegment chunkSegment = CodeSegment.builder()
                            .id(chunkId)
                            .relativeFilePath(relativeFilePath)
                            .content(chunkContent)
                            .contentChecksum(calculateChecksum(chunkContent))
                            .startLine(originalStartLine)
                            .endLine(originalEndLine)
                            .type(type)
                            .entityName(entityName)
                            .fileLastModified(fileLastModified)
                            .parentId(parentId)
                            .parentFqn(fqnStack.isEmpty() ? null : fqnStack.peek())
                            .metadata(chunkMetadata)
                            .isSubChunk(true)
                            .originalSegmentId(finalizeId(baseSegmentId, originalContent))
                            .chunkNumber(i)
                            .build();
                        if (chunkSegment.getContent() != null && !chunkSegment.getContent().trim().isEmpty()) {
                            collector.add(chunkSegment);
                        }
                    }
                    log.debug("Segment '{}' chunked into {} pieces.", entityName, chunks.size());
                } else {
                    CodeSegment segment = CodeSegment.builder()
                        .id(finalizeId(baseSegmentId, originalContent))
                        .relativeFilePath(relativeFilePath)
                        .content(originalContent)
                        .contentChecksum(calculateChecksum(originalContent))
                        .startLine(originalStartLine)
                        .endLine(originalEndLine)
                        .type(type)
                        .entityName(entityName)
                        .fileLastModified(fileLastModified)
                        .parentId(parentId)
                        .parentFqn(fqnStack.isEmpty() ? null : fqnStack.peek())
                        .metadata(baseMetadata)
                        .isSubChunk(false)
                        .build();
                    if (segment.getContent() != null && !segment.getContent().trim().isEmpty() && segment.getStartLine() > 0) {
                        collector.add(segment);
                    } else {
                        log.warn("Skipping potentially invalid segment (empty content or invalid line numbers): id={}, type={}, name={}, lines={}-{}",
                            segment.getId(), segment.getType(), segment.getEntityName(), segment.getStartLine(), segment.getEndLine());
                    }
                }
            }

            private List<String> splitContentIntoChunks(String content, int maxChunkSize, int overlapSize) {
                List<String> chunks = new ArrayList<>();
                if (content == null || content.isEmpty() || maxChunkSize <= 0) {
                    return chunks;
                }
                if (overlapSize >= maxChunkSize) {
                    overlapSize = maxChunkSize / 3;
                }
                int currentPos = 0;
                while (currentPos < content.length()) {
                    int endPos = Math.min(currentPos + maxChunkSize, content.length());
                    chunks.add(content.substring(currentPos, endPos));
                    currentPos = endPos - overlapSize;
                    if (currentPos < endPos - maxChunkSize + overlapSize && endPos < content.length()) {
                        currentPos = endPos;
                    }
                    if (endPos == content.length()) {
                        break;
                    }
                }
                return chunks;
            }

            @Override
            public void visit(PackageDeclaration n, List<CodeSegment> collector) {
                currentPackage = n.getNameAsString();
                log.trace("Skipping indexing of PACKAGE_DECLARATION: {} in file {}", n.getNameAsString(), relativeFilePath);
            }

            @Override
            public void visit(ImportDeclaration n, List<CodeSegment> collector) {
                log.trace("Skipping indexing of IMPORT_DECLARATION: {} in file {}", n.getNameAsString(), relativeFilePath);
                if (!n.isAsterisk()) {
                    currentImports.put(n.getName().getIdentifier(), n.getNameAsString());
                }
            }

            @Override
            public void visit(ModuleDeclaration n, List<CodeSegment> collector) {
                String moduleName = n.getNameAsString();
                String baseId = generateBaseId(n, SegmentType.MODULE_DECLARATION, moduleName);
                String currentFqn = calculateFqn(SegmentType.MODULE_DECLARATION, moduleName, "");
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("isOpen", n.isOpen());
                n.getAnnotations().forEach(ann -> addAnnotationMetadata(metadata, ann));
                addSegment(n, SegmentType.MODULE_DECLARATION, moduleName, metadata, collector);
                String idForStack = finalizeId(baseId, n.toString());
                fqnStack.push(currentFqn);
                idStack.push(idForStack);
                super.visit(n, collector);
                idStack.pop();
                fqnStack.pop();
            }

            @Override
            public void visit(ModuleRequiresDirective n, List<CodeSegment> collector) {
                log.trace("Skipping indexing of MODULE_DIRECTIVE_REQUIRES: {} in file {}", n.getNameAsString(), relativeFilePath);
            }

            @Override
            public void visit(ModuleExportsDirective n, List<CodeSegment> collector) {
                log.trace("Skipping indexing of MODULE_DIRECTIVE_EXPORTS: {} in file {}", n.getNameAsString(), relativeFilePath);
            }

            @Override
            public void visit(ModuleOpensDirective n, List<CodeSegment> collector) {
                log.trace("Skipping indexing of MODULE_DIRECTIVE_OPENS: {} in file {}", n.getNameAsString(), relativeFilePath);
            }

            @Override
            public void visit(ModuleUsesDirective n, List<CodeSegment> collector) {
                log.trace("Skipping indexing of MODULE_DIRECTIVE_USES: {} in file {}", n.getNameAsString(), relativeFilePath);
            }

            @Override
            public void visit(ModuleProvidesDirective n, List<CodeSegment> collector) {
                log.trace("Skipping indexing of MODULE_DIRECTIVE_PROVIDES: {} in file {}", n.getNameAsString(), relativeFilePath);
            }

            @Override
            public void visit(ClassOrInterfaceDeclaration n, List<CodeSegment> collector) {
                String entityName = n.getNameAsString();
                SegmentType type = n.isInterface() ? SegmentType.INTERFACE : SegmentType.CLASS;
                String baseId = generateBaseId(n, type, entityName);
                String parentFqnForChildren = fqnStack.isEmpty() ? currentPackage : fqnStack.peek();
                String currentFqn = calculateFqn(type, entityName, parentFqnForChildren);
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("modifiers", n.getModifiers().stream().map(Modifier::getKeyword).map(Enum::name).collect(Collectors.joining(" ")));
                n.getAnnotations().forEach(ann -> addAnnotationMetadata(metadata, ann));
                if (n.isClassOrInterfaceDeclaration()) {
                    ClassOrInterfaceDeclaration cid = n.asClassOrInterfaceDeclaration();
                    cid.getExtendedTypes().ifNonEmpty(
                        sup -> metadata.put("superclass", sup.stream().map(ClassOrInterfaceType::getNameAsString).collect(Collectors.joining(", "))));
                    cid.getImplementedTypes().ifNonEmpty(
                        impl -> metadata.put("interfaces", impl.stream().map(ClassOrInterfaceType::getNameAsString).collect(Collectors.joining(", "))));
                    cid.getTypeParameters()
                        .ifNonEmpty(tp -> metadata.put("typeParameters", tp.stream().map(Node::toString).collect(Collectors.joining(", "))));
                }
                addSegment(n, type, entityName, metadata, collector);
                String idForStack = finalizeId(baseId, n.toString());
                fqnStack.push(currentFqn);
                idStack.push(idForStack);
                super.visit(n, collector);
                idStack.pop();
                fqnStack.pop();
            }

            @Override
            public void visit(EnumDeclaration n, List<CodeSegment> collector) {
                String entityName = n.getNameAsString();
                SegmentType type = SegmentType.ENUM;
                String baseId = generateBaseId(n, type, entityName);
                String parentFqnForChildren = fqnStack.isEmpty() ? currentPackage : fqnStack.peek();
                String currentFqn = calculateFqn(type, entityName, parentFqnForChildren);
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("modifiers", n.getModifiers().stream().map(Modifier::getKeyword).map(Enum::name).collect(Collectors.joining(" ")));
                n.getAnnotations().forEach(ann -> addAnnotationMetadata(metadata, ann));
                n.getImplementedTypes().ifNonEmpty(
                    impl -> metadata.put("interfaces", impl.stream().map(ClassOrInterfaceType::getNameAsString).collect(Collectors.joining(", "))));
                addSegment(n, type, entityName, metadata, collector);
                String idForStack = finalizeId(baseId, n.toString());
                fqnStack.push(currentFqn);
                idStack.push(idForStack);
                super.visit(n, collector);
                idStack.pop();
                fqnStack.pop();
            }

            @Override
            public void visit(EnumConstantDeclaration n, List<CodeSegment> collector) {
                String entityName = n.getNameAsString();
                SegmentType type = SegmentType.FIELD;
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("modifiers", "PUBLIC STATIC FINAL");
                if (!fqnStack.isEmpty()) {
                    String parentFqn = fqnStack.peek();
                    String enumNameOnly = parentFqn.substring(parentFqn.lastIndexOf('.') + 1);
                    metadata.put("fieldType", enumNameOnly);
                }
                n.getAnnotations().forEach(ann -> addAnnotationMetadata(metadata, ann));
                if (!n.getArguments().isEmpty()) {
                    metadata.put("arguments", n.getArguments().stream().map(Node::toString).collect(Collectors.joining(", ")));
                }
                addSegment(n, type, entityName, metadata, collector);
            }

            @Override
            public void visit(AnnotationDeclaration n, List<CodeSegment> collector) {
                String entityName = n.getNameAsString();
                SegmentType type = SegmentType.ANNOTATION;
                String baseId = generateBaseId(n, type, entityName);
                String parentFqnForChildren = fqnStack.isEmpty() ? currentPackage : fqnStack.peek();
                String currentFqn = calculateFqn(type, entityName, parentFqnForChildren);
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("modifiers", n.getModifiers().stream().map(Modifier::getKeyword).map(Enum::name).collect(Collectors.joining(" ")));
                n.getAnnotations().forEach(ann -> addAnnotationMetadata(metadata, ann));
                addSegment(n, type, entityName, metadata, collector);
                String idForStack = finalizeId(baseId, n.toString());
                fqnStack.push(currentFqn);
                idStack.push(idForStack);
                super.visit(n, collector);
                idStack.pop();
                fqnStack.pop();
            }

            @Override
            public void visit(AnnotationMemberDeclaration n, List<CodeSegment> collector) {
                String entityName = n.getNameAsString();
                SegmentType type = SegmentType.METHOD;
                Map<String, Object> metadata = new HashMap<>();
                String modifiers = n.getModifiers().stream().map(Modifier::getKeyword).map(Enum::name).collect(Collectors.joining(" "));
                if (modifiers.isEmpty()) {
                    modifiers = "PUBLIC";
                } else if (!modifiers.contains("PUBLIC") && !modifiers.contains("PRIVATE") && !modifiers.contains("PROTECTED")) {
                    modifiers = "PUBLIC " + modifiers;
                }
                metadata.put("modifiers", modifiers.trim());
                n.getAnnotations().forEach(ann -> addAnnotationMetadata(metadata, ann));
                metadata.put("returnType", n.getType().asString());
                n.getDefaultValue().ifPresent(def -> metadata.put("defaultValue", def.toString()));
                addSegment(n, type, entityName, metadata, collector);
            }

            @Override
            public void visit(MethodDeclaration n, List<CodeSegment> collector) {
                String entityName = n.getNameAsString();
                SegmentType type = SegmentType.METHOD;
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("modifiers", n.getModifiers().stream().map(Modifier::getKeyword).map(Enum::name).collect(Collectors.joining(" ")));
                n.getAnnotations().forEach(ann -> addAnnotationMetadata(metadata, ann));
                metadata.put("returnType", n.getTypeAsString());
                metadata.put("parameters", n.getParameters().stream().map(Node::toString).collect(Collectors.joining(", ")));
                n.getThrownExceptions().ifNonEmpty(ex -> metadata.put("throws", ex.stream().map(Node::toString).collect(Collectors.joining(", "))));
                n.getTypeParameters().ifNonEmpty(tp -> metadata.put("typeParameters", tp.stream().map(Node::toString).collect(Collectors.joining(", "))));
                addSegment(n, type, entityName, metadata, collector);
            }

            @Override
            public void visit(ConstructorDeclaration n, List<CodeSegment> collector) {
                String entityName = n.getNameAsString();
                SegmentType type = SegmentType.CONSTRUCTOR;
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("modifiers", n.getModifiers().stream().map(Modifier::getKeyword).map(Enum::name).collect(Collectors.joining(" ")));
                n.getAnnotations().forEach(ann -> addAnnotationMetadata(metadata, ann));
                metadata.put("parameters", n.getParameters().stream().map(Node::toString).collect(Collectors.joining(", ")));
                n.getThrownExceptions().ifNonEmpty(ex -> metadata.put("throws", ex.stream().map(Node::toString).collect(Collectors.joining(", "))));
                n.getTypeParameters().ifNonEmpty(tp -> metadata.put("typeParameters", tp.stream().map(Node::toString).collect(Collectors.joining(", "))));
                addSegment(n, type, entityName, metadata, collector);
            }

            @Override
            public void visit(FieldDeclaration n, List<CodeSegment> collector) {
                String commonModifiers = n.getModifiers().stream().map(Modifier::getKeyword).map(Enum::name).collect(Collectors.joining(" "));
                List<AnnotationExpr> commonAnnotations = n.getAnnotations();
                String fieldTypeString = n.getElementType().asString();
                for (VariableDeclarator var : n.getVariables()) {
                    String entityName = var.getNameAsString();
                    SegmentType type = SegmentType.FIELD;
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("modifiers", commonModifiers);
                    commonAnnotations.forEach(ann -> addAnnotationMetadata(metadata, ann));
                    metadata.put("fieldType", fieldTypeString);
                    var.getInitializer().ifPresent(initializer -> metadata.put("initializer", initializer.toString()));
                    addSegment(n, type, entityName, metadata, collector);
                }
            }

            @Override
            public void visit(InitializerDeclaration n, List<CodeSegment> collector) {
                SegmentType type = n.isStatic() ? SegmentType.STATIC_BLOCK : SegmentType.INSTANCE_BLOCK;
                String entityName = n.isStatic() ? "static_initializer" : "instance_initializer";
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("static", n.isStatic());
                n.getAnnotations().forEach(ann -> addAnnotationMetadata(metadata, ann));
                addSegment(n, type, entityName, metadata, collector);
            }

            private void addAnnotationMetadata(Map<String, Object> metadata, AnnotationExpr ann) {
                @SuppressWarnings("unchecked")
                List<Map<String, String>> annotations =
                    (List<Map<String, String>>) metadata.computeIfAbsent("annotations", k -> new ArrayList<Map<String, String>>());
                Map<String, String> annInfo = new HashMap<>();
                annInfo.put("name", ann.getNameAsString());
                annInfo.put("details", ann.toString());
                annotations.add(annInfo);
            }

        }
    }
