import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class EvalChunkRecall {

    private static final Path BASE = Path.of("src/main/java/com/library/agent/beir_scifact_eval/exported/scifact");
    private static final Path CHUNK_TEST_SET = BASE.resolve("chunk_test_set.jsonl");
    private static final String API = "http://localhost:8084/eval/beir/scifact/search-chunks";
    private static final String TOKEN = "vTP92D7HgzD8t3THoJwB2Qo_WwtgQcezlbZxD6boYW0";
    private static final int[] K_VALUES = {1, 3, 5, 10, 50, 100};

    public static void main(String[] args) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, String> queries = new LinkedHashMap<>();
        Map<String, Set<String>> qrels = new LinkedHashMap<>();

        for (String line : Files.readAllLines(CHUNK_TEST_SET, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode row = objectMapper.readTree(line);
            String queryId = row.path("query_id").asText();
            String query = row.path("query").asText();
            String chunkId = row.path("chunk_id").asText();

            queries.putIfAbsent(queryId, query);
            qrels.computeIfAbsent(queryId, ignored -> new LinkedHashSet<>()).add(chunkId);
        }

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        Map<Integer, Double> recallSums = new LinkedHashMap<>();
        for (int k : K_VALUES) {
            recallSums.put(k, 0.0);
        }

        int searched = 0;
        for (Map.Entry<String, String> entry : queries.entrySet()) {
            String queryId = entry.getKey();
            String query = entry.getValue();
            Set<String> relevantChunks = qrels.get(queryId);
            List<String> retrievedChunks = searchChunks(objectMapper, client, query);

            for (int k : K_VALUES) {
                recallSums.compute(k, (ignored, sum) ->
                        sum + recallAtK(relevantChunks, retrievedChunks, k)
                );
            }

            searched++;
            if (searched % 20 == 0) {
                System.out.println("searched " + searched + "/" + queries.size());
            }
        }

        System.out.println("chunk qrel queries: " + queries.size());
        System.out.println("chunk qrel rows: " + qrels.values().stream().mapToInt(Set::size).sum());
        System.out.println();
        System.out.println("Recall:");
        for (int k : K_VALUES) {
            double recall = recallSums.get(k) / queries.size();
            System.out.printf("Recall@%d: %.4f%n", k, recall);
        }
    }

    private static List<String> searchChunks(ObjectMapper objectMapper, HttpClient client, String query) throws Exception {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("query", query);
        requestBody.put("topK", 100);
        requestBody.put("vectorTopK", 300);
        requestBody.put("keywordTopK", 300);
        requestBody.put("candidateTopK", 300);
        requestBody.put("useRerank", true);
        requestBody.put("rerankTopN", 100);
        requestBody.put("minRerankScore", 0.0);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("Search failed, status=" + response.statusCode() + ", body=" + response.body());
        }

        List<String> chunkIds = new ArrayList<>();
        JsonNode hits = objectMapper.readTree(response.body()).path("hits");
        if (hits.isArray()) {
            for (JsonNode hit : hits) {
                chunkIds.add(hit.path("chunkId").asText());
            }
        }
        return chunkIds;
    }

    private static double recallAtK(Set<String> relevantChunks, List<String> retrievedChunks, int k) {
        if (relevantChunks == null || relevantChunks.isEmpty()) {
            return 0.0;
        }

        int hits = 0;
        int limit = Math.min(k, retrievedChunks.size());
        Set<String> seen = new LinkedHashSet<>();

        for (int i = 0; i < limit; i++) {
            String chunkId = retrievedChunks.get(i);
            if (seen.add(chunkId) && relevantChunks.contains(chunkId)) {
                hits++;
            }
        }

        return hits / (double) relevantChunks.size();
    }
}
