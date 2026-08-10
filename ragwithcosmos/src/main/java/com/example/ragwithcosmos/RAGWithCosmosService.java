package com.example.ragwithcosmos;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.FeedResponse;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.models.PartitionKeyBuilder;
import com.azure.cosmos.models.SqlParameter;
import com.azure.cosmos.models.SqlQuerySpec;
import com.azure.cosmos.util.CosmosPagedIterable;
import com.example.ragwithcosmos.pojo.Chunk;
import com.example.ragwithcosmos.pojo.DocumentId;
import com.example.ragwithcosmos.pojo.QueryResult;
import com.example.ragwithcosmos.pojo.Response;
import com.example.ragwithcosmos.pojo.TestResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class RAGWithCosmosService {
    private static final Logger logger = LoggerFactory.getLogger(RAGWithCosmosService.class);

    @Value("${azure.cosmos.endpoint}")
    private String endPoint;
    @Value("${azure.cosmos.database}")
    private String databaseName;
    @Value("${azure.cosmos.container}")
    private String containerName;
    @Value("${azure.cosmos.key}")
    private String key;

    private CosmosContainer getContainer() {
        CosmosClient client = new CosmosClientBuilder()
                .endpoint(endPoint)
                .key(key)
                .buildClient();

        return client.getDatabase(databaseName).getContainer(containerName);
    }

    public List<Chunk> loadJsonFile(String filename) {
        List<Chunk> chunks = new ArrayList<>();

        try {
            InputStream inputStream;
            File file = new File(filename);

            if (file.exists()) {
                inputStream = new FileInputStream(file);
            } else {
                Resource resource = new ClassPathResource(filename);
                inputStream = resource.getInputStream();
            }

            try (InputStream stream = inputStream) {
                ObjectMapper objectMapper = new ObjectMapper();
                JsonNode rootNode = objectMapper.readTree(stream);

                if (rootNode.has("chunks") && rootNode.get("chunks").isArray()) {
                    JsonNode chunksNode = rootNode.get("chunks");
                    for (JsonNode node : chunksNode) {
                        Chunk chunk = objectMapper.treeToValue(node, Chunk.class);
                        chunks.add(chunk);
                    }
                    logger.info("Loaded JSON file {} with {} chunks", filename, chunks.size());
                }
            }
        } catch (IOException ex) {
            logger.error("Failed to load JSON file {}", filename, ex);
        }

        return chunks;
    }

    public List<String> getAllDocumentIds() {
        List<String> results = new ArrayList<>();

        try {
            CosmosContainer container = getContainer();
            String query = "SELECT DISTINCT c.documentId from c";
            Iterable<FeedResponse<DocumentId>> queryResponses = container.queryItems(query, null, DocumentId.class)
                    .iterableByPage();

            for (FeedResponse<DocumentId> feedResponse : queryResponses) {
                results = feedResponse.getResults().stream().map(DocumentId::documentId).toList();
                System.out.println(results);
            }
        } catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
        }
        return results;
    }

    public List<Chunk> getChunksByDocument(String documentId) {
        CosmosContainer container = getContainer();
        CosmosQueryRequestOptions options = new CosmosQueryRequestOptions();
        options.setPartitionKey(new PartitionKeyBuilder().add(documentId).build());
        List<Chunk> chunks = new ArrayList<>();

        String query = """
                    SELECT c.id, c.content, c.metadata, c.chunkIndex, c.createdAt
                    FROM c
                    WHERE c.documentId = @documentId
                    ORDER BY c.chunkIndex
                    OFFSET 0 LIMIT @limit
                """;

        SqlQuerySpec querySpec = new SqlQuerySpec(query, Arrays.asList(
                new SqlParameter("@documentId", documentId), // Automatically handles string wrapping
                new SqlParameter("@limit", 100) // Automatically handles integers
        ));

        Iterable<FeedResponse<Chunk>> queryResponses = container.queryItems(querySpec, options, Chunk.class)
                .iterableByPage();

        for (FeedResponse<Chunk> response : queryResponses) {
            chunks = response.getResults();
            logger.info("Chunks :: {}", chunks);
        }
        return chunks;
    }

    public List<String> getAllCategories() {
        List<String> categories = new ArrayList<>();
        try {
            CosmosContainer container = getContainer();
            String query = "SELECT DISTINCT c.metadata.category FROM c WHERE IS_DEFINED(c.metadata.category)";

            Iterable<FeedResponse<Category>> queryResponses = container.queryItems(query, null, Category.class)
                    .iterableByPage();

            for (FeedResponse<Category> response : queryResponses) {
                categories = response.getResults().stream().map(Category::category).sorted().toList();
                logger.info("Categories :: {}", categories);
            }
        } catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
        }
        return categories;

    }

    public List<Chunk> searchChunksByMetadata(Map<String, String> filters) {
        List<Chunk> chunks = new ArrayList<>();
        CosmosContainer container = getContainer();
        List<SqlParameter> parameters = new ArrayList<>();
        StringBuilder where_clauses = new StringBuilder();

        if (filters.get("source") != null) {
            if (!where_clauses.isEmpty()) {
                where_clauses.append(" AND ");
            }
            where_clauses.append("c.metadata.source = @source");
            parameters.add(new SqlParameter("@source", filters.get("source")));
        }
        if (filters.get("category") != null) {
            if (!where_clauses.isEmpty()) {
                where_clauses.append(" AND ");
            }
            where_clauses.append("c.metadata.category = @category");
            parameters.add(new SqlParameter("@category", filters.get("category")));
        }
        if (filters.get("tag") != null) {
            if (!where_clauses.isEmpty()) {
                where_clauses.append(" AND ");
            }
            where_clauses.append("ARRAY_CONTAINS(c.metadata.tags, @tag)");
            parameters.add(new SqlParameter("@tag", filters.get("tag")));
        }
        if (where_clauses.isEmpty()) {
            where_clauses.append("1=1");
        }
        parameters.add(new SqlParameter("@limit", 10));

        String query = """
                    SELECT c.id, c.documentId, c.content, c.metadata, c.chunkIndex
                    FROM c
                    WHERE %s
                    OFFSET 0 LIMIT @limit
                """.formatted(where_clauses.toString());

        SqlQuerySpec sqlQuerySpec = new SqlQuerySpec(query, parameters);

        Iterable<FeedResponse<Chunk>> queryResponse = container.queryItems(sqlQuerySpec, null, Chunk.class)
                .iterableByPage();

        for (FeedResponse<Chunk> response : queryResponse) {
            chunks = response.getResults();
            logger.info("Chunks:: {}", chunks);
        }
        return chunks;
    }

    public QueryResult executeQuery(String sqlQuery) {
        if (!sqlQuery.startsWith("select")) {
            return new QueryResult("False", new ArrayList<>(), 0, "Only select queries are supported");
        }
        try {
            CosmosContainer container = getContainer();
            // Execute query (using Map.class to capture generic dynamic JSON fields like
            // Python does)
            CosmosPagedIterable<Map> items = container.queryItems(sqlQuery, null, Map.class);

            // Convert iterable to a concrete List
            List<Map> results = new ArrayList<>();
            items.forEach(results::add);

            QueryResult queryResult = new QueryResult("True", results, results.size(), null);
            return queryResult;
        } catch (Exception ex) {
            return new QueryResult("False", new ArrayList<>(), 0, ex.getMessage());
        }
    }

    public Chunk getChunkById(String document_id, String chunk_id) {
        CosmosContainer container = getContainer();
        try {
            return container.readItem(chunk_id, new PartitionKey(document_id), Chunk.class).getItem();
        } catch (CosmosException ex) {
            logger.error(ex.getMessage(), ex);
            return null;
        }
    }

    public Object runTestWorkflow() {
        List<TestResult> results = new ArrayList<>();
        int stored_count = 0;
        float total_ru = 0f;
        // Test 1: Store document chunks
        try {
            var chunks = loadJsonFile("test_chunks.json");
            for (Chunk chunk : chunks) {
               var response = storeDocumentChunk(chunk);
               stored_count += 1;
               total_ru+=response.ruCharge();
            }
            results.add(new TestResult("Store Document Chunks", "passed", 
            String.format("Stored %d chunks, total RU:%.2f",stored_count,total_ru)));
            
        } catch (Exception ex) {
            logger.error(ex.getMessage(),ex);
            results.add(new TestResult("Store Document Chunks", "failed",ex.getMessage())); 
        }

        // Test 2: Get chunks by document
        try {
            var chunks = getChunksByDocument("test-doc-001");
            if (chunks.size() >= 2) {
                results.add(new TestResult("Get Chunks by Document", "passed",
                        String.format("Retrieved %d chunks for test-doc-001", chunks.size())));
            } else {
                results.add(new TestResult("Get Chunks by Document", "failed",
                        String.format("Expected at least 2 chunks, got %d", chunks.size())));
            }
        } catch (Exception ex) {
            results.add(new TestResult("Get Chunks by Document", "failed", ex.getMessage()));
        }

        // Test 3: Search by metadata (category)
        try {
            var chunks = searchChunksByMetadata(Map.of("category", "databases"));
            if (chunks.size() > 2) {
                results.add(new TestResult("Search by Category", "passed",
                        String.format("Found %d chunks with category 'databases'", chunks.size())));
            } else {
                results.add(new TestResult("Search by Category", "failed",
                        String.format("Expected at least 2 chunks, got %d", chunks.size())));
            }
        } catch (Exception ex) {
            results.add(new TestResult("Search by Category", "failed", ex.getMessage()));
        }

        // Test 4: Search by metadata (tags)
        try {
            var chunks = searchChunksByMetadata(Map.of("tag", "serverless"));
            if (chunks.size() >= 2) {
                results.add(new TestResult("Search by Tag", "passed",
                        String.format("Found %d chunks with tag 'serverless'", chunks.size())));
            } else {
                results.add(new TestResult("Search by Tag", "failed",
                        String.format("Expected at least 1 chunk, got %d", chunks.size())));
            }
        } catch (Exception ex) {
            results.add(new TestResult("Search by Tag", "failed", ex.getMessage()));
        }

        // Test 5: Point read
        try {
            var chunk = getChunkById("test-doc-001", "test-doc-001-chunk-0");

            if (chunk != null && chunk.content() != null) {
                results.add(new TestResult("Point Read (Get Chunk by ID)", "passed",
                        String.format("Retrieved chunk with %d characters", chunk.content().length())));
            } else {
                results.add(new TestResult("Point Read (Get Chunk by ID)", "failed", "Chunk not found or empty"));
            }
        } catch (Exception ex) {
            results.add(new TestResult("Point Read (Get Chunk by ID)", "failed", ex.getMessage()));
        }

        return results;

    }

    private Response storeDocumentChunk(Chunk chunk) {
        CosmosContainer container = getContainer(); 
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");

        Chunk chnk = new Chunk(chunk.id(), chunk.documentId(), chunk.content(), chunk.metadata(),
                               chunk.embedding() !=null ? chunk.embedding():new ArrayList<>(),
                               chunk.metadata().chunkIndex(),LocalDateTime.now().format(formatter));

        CosmosItemResponse<Chunk> response = container.upsertItem(chnk,new PartitionKey(chnk.documentId()),null);        
        var ru_charge = Float.valueOf(response.getResponseHeaders().get("x-ms-request-charge"));

        return new Response(chnk.id(),chnk.documentId(),ru_charge);
    }
}
