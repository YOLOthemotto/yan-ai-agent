package com.ym.yanaiagent.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.List;

/**
 * 使用 PgVector 保存文档并执行语义检索。
 */
@Service
@Profile("session-postgres")
public class RagService {

    private final VectorStore vectorStore;

    public RagService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void addDocuments(List<Document> documents) {
        Assert.notEmpty(documents, "Documents must not be empty");
        vectorStore.add(documents);
    }

    public List<Document> search(String query) {
        Assert.hasText(query, "Query must not be blank");
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(3)
                .build());
    }
}
