package com.example.vectorsearch;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.vectorsearch.model.Query;
import com.example.vectorsearch.util.Mapper;

@Controller
public class VectorSearchController {
    private static final Logger logger = LoggerFactory.getLogger(VectorSearchController.class);

    private final VectorSearchService vectorSearchService;

    VectorSearchController(VectorSearchService vectorSearchService) {
        this.vectorSearchService = vectorSearchService;
    }

    /**
     * Display the main page
     * 
     * @param model
     * @return
     */
    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("document_ids", vectorSearchService.getAllDocumentIds());
        model.addAttribute("categories", vectorSearchService.getAllCategories());
        model.addAttribute("queries", Mapper.mapQueriesToQueryDTO(vectorSearchService.getSampleQueries()));

        return "index";
    }

    /**
     * Load sample documents with embeddings into the database.
     * 
     * @param model
     * @return
     */
    @PostMapping("/load-data")
    public String loadData(Model model) {
        try {
            var response = vectorSearchService.loadData();
            String success = String.format("Successfully loaded %d tickets with embeddings! Total RU: %.2f",
                    response.loaded_count(), response.totalRU());
            model.addAttribute("messages", Map.of(success,"success"));
            return "index";

        } catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
            String failed = String.format("Error loading data: %s", ex.getMessage());
            model.addAttribute("messages", Map.of(failed,"error"));
            return "redirect:/index";
        }
    }

    /**
     * Perform vector similarity search using a pre-computed query vector.
     * 
     * @param queryId
     * @param topN
     * @param model
     * @return
     */
    @PostMapping("/vector-search")
    public String vectorSearch(@RequestParam("query_id") String queryId,
            @RequestParam("top_n") int topN,
            Model model) {
        if (queryId.isBlank()) {
            logger.error("Query not found");
            return "redirect:/index";
        }
        try {
            // Get the query embedding from sample data
            List<Query> queries = vectorSearchService.getSampleQueries();
            Optional<Query> optionalQuery = queries.stream().filter(q -> q.id().equals(queryId)).findFirst();

            if (optionalQuery.isEmpty()) {
                logger.error("Query not found");
                return "redirect:/index";
            }
            Query query = optionalQuery.get();
            var similarVectors = vectorSearchService.vectorSimilaritySearch(query.embedding(), topN);
            var document_ids = vectorSearchService.getAllDocumentIds();
            var categories = vectorSearchService.getAllCategories();

            model.addAllAttributes(Map.of(
                    "documentIds", document_ids,
                    "categories", categories,
                    "queries", Mapper.mapQueriesToQueryDTO(queries),
                    "vectorResult", similarVectors,
                    "vectorQuery", query.description(),
                    "vectorTopN", topN));

            return "index";
        } catch (Exception ex) {
            logger.error("Error performing vector search {}", ex);
            return "redirect:/index";
        }
    }

    /**
     * Perform filtered vector search combining metadata and similarity.
     * 
     * @param queryId
     * @param category
     * @param topN
     * @param model
     * @return
     */
    @PostMapping("/filtered-vector-search")
    public String filteredVectorSearch(@RequestParam("filtered_query_id") String queryId,
            @RequestParam(value = "filter_category", required = false) String category,
            @RequestParam("filtered_top_n") int topN,
            Model model) {

        if (queryId.isBlank()) {
            logger.error("Please select a query");
            return "redirect:/index";
        }
        try {
            // Get the query embedding from sample data
            var queries = vectorSearchService.getSampleQueries();
            Optional<Query> optionalQuery = queries.stream().filter(q -> q.id().equals(queryId)).findFirst();

            if (optionalQuery.isEmpty()) {
                logger.error("Query not found");
                return "redirect:/index";
            }
            Query query = optionalQuery.get();

            var filarteredVector = vectorSearchService.filteredVectorSearch(query.embedding(), category, topN);
            var document_ids = vectorSearchService.getAllDocumentIds();
            var categories = vectorSearchService.getAllCategories();

            model.addAllAttributes(Map.of(
                    "documentIds", document_ids,
                    "categories", categories,
                    "queries", Mapper.mapQueriesToQueryDTO(queries),
                    "filteredResult", filarteredVector,
                    "filteredQuery", query.description(),
                    "filteredCategory", category,
                    "filteredTopN", topN));

            return "index";
        } catch (Exception ex) {
            logger.error("Error performing filtered search: {}", ex);
            return "redirect:/index";
        }
    }
}
