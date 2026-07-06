import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BuildChunkTestSet {

    private static final Path BASE = Path.of("src/main/java/com/library/agent/beir_scifact_eval");
    private static final Path CORPUS_PATH = BASE.resolve("datasets/scifact/corpus.jsonl");
    private static final Path QUERIES_PATH = BASE.resolve("datasets/scifact/queries.jsonl");
    private static final Path QRELS_PATH = BASE.resolve("exported/scifact/qrels.jsonl");
    private static final Path OUTPUT_DIR = BASE.resolve("exported/scifact");
    private static final Path JSONL_OUTPUT = OUTPUT_DIR.resolve("chunk_test_set.jsonl");
    private static final Path TSV_OUTPUT = OUTPUT_DIR.resolve("chunk_test_set.tsv");
    private static final Path MISSING_OUTPUT = OUTPUT_DIR.resolve("chunk_test_set_missing_chunks.jsonl");

    private static final String JDBC_URL = "jdbc:postgresql://localhost:5432/rag_db";
    private static final String JDBC_USER = "zq";
    private static final String JDBC_PASSWORD = "zq2892294059";

    public static void main(String[] args) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, String> queries = loadQueries(objectMapper);
        Map<String, String> corpusTitles = loadCorpusTitles(objectMapper);
        List<Qrel> qrels = loadQrels(objectMapper);

        Files.createDirectories(OUTPUT_DIR);

        int rows = 0;
        int missingQueries = 0;
        int missingCorpusDocs = 0;
        int qrelsWithoutChunks = 0;
        Set<String> coveredQueries = new HashSet<>();
        Set<String> coveredDocs = new HashSet<>();

        try (
                Connection connection = DriverManager.getConnection(JDBC_URL, JDBC_USER, JDBC_PASSWORD);
                BufferedWriter jsonlWriter = Files.newBufferedWriter(JSONL_OUTPUT, StandardCharsets.UTF_8);
                BufferedWriter tsvWriter = Files.newBufferedWriter(TSV_OUTPUT, StandardCharsets.UTF_8);
                BufferedWriter missingWriter = Files.newBufferedWriter(MISSING_OUTPUT, StandardCharsets.UTF_8)
        ) {
            tsvWriter.write("query_id\tquery\tdoc_id\tdoc_title\tchunk_id\tchunk_index\tchunk_text\n");

            for (Qrel qrel : qrels) {
                String query = queries.get(qrel.queryId());
                String title = corpusTitles.get(qrel.docId());
                if (query == null) {
                    missingQueries++;
                    query = "";
                }
                if (title == null) {
                    missingCorpusDocs++;
                    title = "";
                }

                List<ChunkRow> chunks = loadChunks(connection, qrel.docId());
                if (chunks.isEmpty()) {
                    qrelsWithoutChunks++;
                    ObjectNode missing = objectMapper.createObjectNode();
                    missing.put("query_id", qrel.queryId());
                    missing.put("query", query);
                    missing.put("doc_id", qrel.docId());
                    missing.put("doc_title", title);
                    missing.put("expected_file_id", toEvalFileId(qrel.docId()));
                    missing.put("legacy_file_id", -Long.parseLong(qrel.docId()));
                    missing.put("legacy_chunk_count", countChunks(connection, -Long.parseLong(qrel.docId())));
                    missingWriter.write(objectMapper.writeValueAsString(missing));
                    missingWriter.newLine();
                    continue;
                }

                for (ChunkRow chunk : chunks) {
                    coveredQueries.add(qrel.queryId());
                    coveredDocs.add(qrel.docId());

                    ObjectNode row = objectMapper.createObjectNode();
                    row.put("query_id", qrel.queryId());
                    row.put("query", query);
                    row.put("doc_id", qrel.docId());
                    row.put("doc_title", title);
                    row.put("chunk_id", String.valueOf(chunk.chunkId()));
                    row.put("chunk_index", chunk.chunkIndex());
                    row.put("score", qrel.score());
                    row.put("chunk_text", chunk.chunkText());
                    jsonlWriter.write(objectMapper.writeValueAsString(row));
                    jsonlWriter.newLine();

                    tsvWriter.write(tsv(qrel.queryId()));
                    tsvWriter.write('\t');
                    tsvWriter.write(tsv(query));
                    tsvWriter.write('\t');
                    tsvWriter.write(tsv(qrel.docId()));
                    tsvWriter.write('\t');
                    tsvWriter.write(tsv(title));
                    tsvWriter.write('\t');
                    tsvWriter.write(String.valueOf(chunk.chunkId()));
                    tsvWriter.write('\t');
                    tsvWriter.write(String.valueOf(chunk.chunkIndex()));
                    tsvWriter.write('\t');
                    tsvWriter.write(tsv(chunk.chunkText()));
                    tsvWriter.newLine();

                    rows++;
                }
            }
        }

        System.out.println("queries=" + queries.size());
        System.out.println("corpus_docs=" + corpusTitles.size());
        System.out.println("qrels=" + qrels.size());
        System.out.println("chunk_test_rows=" + rows);
        System.out.println("covered_queries=" + coveredQueries.size());
        System.out.println("covered_docs=" + coveredDocs.size());
        System.out.println("missing_queries=" + missingQueries);
        System.out.println("missing_corpus_docs=" + missingCorpusDocs);
        System.out.println("qrels_without_chunks=" + qrelsWithoutChunks);
        System.out.println("jsonl_output=" + JSONL_OUTPUT.toAbsolutePath());
        System.out.println("tsv_output=" + TSV_OUTPUT.toAbsolutePath());
        System.out.println("missing_output=" + MISSING_OUTPUT.toAbsolutePath());
    }

    private static Map<String, String> loadQueries(ObjectMapper objectMapper) throws Exception {
        Map<String, String> queries = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(QUERIES_PATH, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(line);
                queries.put(node.path("_id").asText(), node.path("text").asText());
            }
        }
        return queries;
    }

    private static Map<String, String> loadCorpusTitles(ObjectMapper objectMapper) throws Exception {
        Map<String, String> corpusTitles = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(CORPUS_PATH, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(line);
                corpusTitles.put(node.path("_id").asText(), node.path("title").asText());
            }
        }
        return corpusTitles;
    }

    private static List<Qrel> loadQrels(ObjectMapper objectMapper) throws Exception {
        List<Qrel> qrels = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(QRELS_PATH, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(line);
                qrels.add(new Qrel(
                        node.path("query_id").asText(),
                        node.path("doc_id").asText(),
                        node.path("score").asInt(1)
                ));
            }
        }
        return qrels;
    }

    private static List<ChunkRow> loadChunks(Connection connection, String docId) throws Exception {
        long evalFileId = toEvalFileId(docId);
        List<ChunkRow> chunks = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT chunk_id, chunk_index, chunk_text FROM text_chunk WHERE file_id = ? ORDER BY chunk_index"
        )) {
            statement.setLong(1, evalFileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    chunks.add(new ChunkRow(
                            resultSet.getLong("chunk_id"),
                            resultSet.getInt("chunk_index"),
                            resultSet.getString("chunk_text")
                    ));
                }
            }
        }

        return chunks;
    }

    private static long toEvalFileId(String docId) {
        return -(Long.parseLong(docId) + 1);
    }

    private static int countChunks(Connection connection, long fileId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(1) FROM text_chunk WHERE file_id = ?"
        )) {
            statement.setLong(1, fileId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt(1);
                }
            }
        }
        return 0;
    }

    private static String tsv(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\t', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ');
    }

    private record Qrel(String queryId, String docId, int score) {
    }

    private record ChunkRow(long chunkId, int chunkIndex, String chunkText) {
    }
}
