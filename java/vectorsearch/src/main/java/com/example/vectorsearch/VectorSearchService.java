package com.example.vectorsearch;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sound.midi.MetaMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.service.invoker.HttpRequestValues.Metadata;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.models.FeedResponse;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.models.SqlParameter;
import com.azure.cosmos.models.SqlQuerySpec;
import com.example.vectorsearch.dto.ItemDTO;
import com.example.vectorsearch.dto.Response;
import com.example.vectorsearch.model.Item;
import com.example.vectorsearch.model.MetaData;
import com.example.vectorsearch.model.Query;
import com.example.vectorsearch.util.DataLoader;

/**
 * Vector search service for storing and retrieving documents with embeddings
 * from Cosmos DB.
 */

@Service
class VectorSearchService {
    private static final Logger logger = LoggerFactory.getLogger(VectorSearchService.class);

    @Value("${spring.cloud.azure.cosmos.endpoint}")
    private String endPoint;

    @Value("${spring.cloud.azure.cosmos.database}")
    private String database;

    @Value("${spring.cloud.azure.cosmos.key}")
    private String key;

    @Value("${azure.cosmos.container}")
    private String containerName;

    private final DataLoader dataLoader;

    private CosmosContainer container;

    VectorSearchService(DataLoader dataLoader) {
        this.dataLoader = dataLoader;
    }

    /**
     * Get a reference to the Cosmos DB container.
     * 
     * @return
     */
    private CosmosContainer getContainer() {
        if (container == null) {
            CosmosClient client = new CosmosClientBuilder()
                    .endpoint(endPoint)
                    .key(key)
                    .buildClient();
            container = client.getDatabase(database).getContainer(containerName);
        }
        return container;
    }

    /**
     * Get a list of unique document IDs from the container.
     * 
     * @return
     */
    List<String> getAllDocumentIds() {
        List<String> documentIds = new ArrayList<>();
        try {
            CosmosContainer cosmosContainer = getContainer();
            String query = "SELECT DISTINCT c.documentId FROM c";
            Iterable<FeedResponse<Item>> queryResponse = cosmosContainer.queryItems(query, null, Item.class)
                    .iterableByPage();

            for (FeedResponse<Item> response : queryResponse) {
                documentIds.addAll(response.getResults().stream().map(Item::documentId).sorted().toList());
            }
            documentIds.stream().sorted().close();
        } catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
        }
        return documentIds;
    }

    /**
     * Get a list of unique categories from the container.
     * 
     * @return
     */
    List<String> getAllCategories() {
        List<String> categories = new ArrayList<>();
        try {
            CosmosContainer cosmosContainer = getContainer();
            String query = "SELECT DISTINCT c.metadata.category FROM c WHERE IS_DEFINED(c.metadata.category)";
            Iterable<FeedResponse<MetaData>> queryResponse = cosmosContainer.queryItems(query, null, MetaData.class)
                    .iterableByPage();

            for (FeedResponse<MetaData> response : queryResponse) {
                categories.addAll(response.getResults().stream().map(MetaData::category).sorted().toList());
            }
            categories.stream().sorted().close();
        } catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
        }
        return categories;
    }

    /**
     * Get the pre-computed query vectors from the sample data file.
     * 
     * @return
     */
    List<Query> getSampleQueries() {

        List<Map<String, Object>> samples = dataLoader.loadSampleJsonData("queries");
        return samples.stream()
                .map(sample -> {
                    Object id = sample.get("id");
                    Object description = sample.get("description");
                    List<Object> embedding = (List) sample.get("embedding");
                    return new Query(
                            id != null ? id.toString() : null,
                            description != null ? description.toString() : null,
                            embedding != null ? embedding.stream().map(obj -> (double) obj).toList() : null);
                })
                .toList();
    }

    Response loadData() {
        int loaded_count = 0;
        float total_ru = 0f;
        List<Map<String, Object>> samples = dataLoader.loadSampleJsonData("documents");
        for (Map<String, Object> sample : samples) {
            String id = (String) sample.get("chunk_id");
            String documentId = (String) sample.get("document_id");
            String content = (String) sample.get("content");
            LinkedHashMap<String,Object> metadataMap =  (LinkedHashMap)sample.get("metadata");
            String source = (String)metadataMap.get("source");
            String category = (String)metadataMap.get("category");
            List<String> tags = (List)metadataMap.get("tags");
            int chunkIndex = metadataMap.get("chunkIndex")!=null? (int)metadataMap.get("chunkIndex"):0;
            MetaData metadata = new MetaData(source, category, tags, chunkIndex);
            List<Double> embedding = (List) sample.get("embedding");
            String createdAt = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);

            var item = new Item(id, documentId, content, metadata, embedding, createdAt, chunkIndex);
            float ruCharge =storeVectorDocument(item);
            loaded_count+=1;
            total_ru+=ruCharge;
        }
        return new Response(loaded_count, total_ru);
    }

    /**
     * Store a document with its vector embedding for similarity search.
     * @param item
     */
    float storeVectorDocument(Item item) {
        CosmosContainer container = getContainer();
        // upsert_item inserts if new, updates if exists (based on id + partition key)
        // This is idempotent - safe to call multiple times with the same data
        CosmosItemResponse<Item> response = container.upsertItem(item);
        // Request Units (RUs) measure the cost of database operations in Cosmos DB
        // Tracking RU consumption helps optimize queries and estimate costs
        return Float.valueOf(response.getResponseHeaders().get("x-ms-request-charge"));
    }

    /**
     * Find documents most similar to the query using vector distance.
     * Uses the VectorDistance function to calculate cosine similarity between
     * the query embedding and document embeddings stored in Cosmos DB.
     * Results are ordered by similarity (lowest distance = most similar).
     * 
     * @param embedding
     * @param topN
     */
    List<ItemDTO> vectorSimilaritySearch(List<Double> embedding, int topN) {
        List<ItemDTO> items = new ArrayList<>();
        CosmosContainer container = getContainer();
        String query = """
                    SELECT TOP @topN
                    c.id,
                    c.documentId,
                    c.content,
                    c.metadata,
                    VectorDistance(c.embedding, @queryVector) AS similarityScore
                    FROM c
                    ORDER BY VectorDistance(c.embedding, @queryVector)
                """;
        SqlParameter firstParameter = new SqlParameter("@topN", topN);
        SqlParameter secondParameter = new SqlParameter("@queryVector", embedding);
        SqlQuerySpec querySpec = new SqlQuerySpec(query, List.of(firstParameter, secondParameter));

        Iterable<FeedResponse<ItemDTO>> queryResponse = container.queryItems(querySpec, null, ItemDTO.class)
                .iterableByPage();

        for (FeedResponse<ItemDTO> response : queryResponse) {
            items.addAll(response.getResults());
        }

        return items;
    }

    /**
     * Combine vector similarity search with metadata filtering.
     * This hybrid approach first filters documents by category (or other metadata),
     * then ranks the filtered results by vector similarity. This is useful for
     * narrowing results to a specific domain before applying semantic search.
     * 
     * @param embedding
     * @param category
     * @param topN
     */
    List<ItemDTO> filteredVectorSearch(List<Double> embedding, String category, int topN) {
        List<ItemDTO> items = new ArrayList<>();
        CosmosContainer container = getContainer();
        // Build WHERE clause for metadata filtering
        // The filter is applied BEFORE vector ranking, reducing the search space
        String where_clause = "";
        List<SqlParameter> parameters = new ArrayList<>();
        parameters.add(new SqlParameter("@topN", topN));
        parameters.add(new SqlParameter("@queryVector", embedding));

        if (!category.isBlank()) {
            where_clause = "WHERE c.metadata.category = @category";
            parameters.add(new SqlParameter("@category", category));
        }

        // Filtered vector search: apply metadata filter, then rank by similarity
        String query = """
                    SELECT TOP @topN
                        c.id,
                        c.documentId,
                        c.content,
                        c.metadata,
                        VectorDistance(c.embedding, @queryVector) AS similarityScore
                    FROM c
                    %s
                    ORDER BY VectorDistance(c.embedding, @queryVector)
                """.formatted(where_clause);

        SqlQuerySpec querySpec = new SqlQuerySpec(query, parameters);
        Iterable<FeedResponse<ItemDTO>> queryResponse = container.queryItems(querySpec, null, ItemDTO.class)
                .iterableByPage();
        for (FeedResponse<ItemDTO> response : queryResponse) {
            items.addAll(response.getResults());
        }
        return items;
    }

}
