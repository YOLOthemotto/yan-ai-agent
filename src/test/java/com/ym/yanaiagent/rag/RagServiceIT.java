package com.ym.yanaiagent.rag;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 手动运行的真实 Embedding 与 PostgreSQL 示例，运行后保留示例文档。
 */
@SpringBootTest
@ActiveProfiles({"local", "session-postgres"})
class RagServiceIT {

    @Resource
    private RagService ragService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Test
    void storesDocumentsAndSearchesByMeaning() {
        List<Document> documents = List.of(
                new Document("00000000-0000-0000-0000-000000000001",
                        "退款规则：商品购买后七天内可以申请退款，款项将原路退回。",
                        Map.of("source", "rag-demo", "category", "refund")),
                new Document("00000000-0000-0000-0000-000000000002",
                        "配送规则：订单发货后通常三天内送达，可以在订单页面查看物流。",
                        Map.of("source", "rag-demo", "category", "delivery")),
                new Document("00000000-0000-0000-0000-000000000003",
                        "账号安全：忘记登录密码时，可以通过绑定的手机号码接收验证码并重置密码。",
                        Map.of("source", "rag-demo", "category", "account")));

        ragService.addDocuments(documents);

        for (Document document : documents) {
            String storedContent = jdbcTemplate.queryForObject(
                    "SELECT content FROM vector_store WHERE id = ?::uuid",
                    String.class, document.getId());
            assertThat(storedContent).isEqualTo(document.getText());
        }

        String query = "我前天买的东西现在不想要了，能把钱退给我吗？";
        List<Document> results = ragService.search(query);

        System.out.println("搜索问题：" + query);
        for (Document result : results) {
            System.out.printf("相似度：%.4f，内容：%s%n", result.getScore(), result.getText());
        }

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().getId()).isEqualTo(documents.getFirst().getId());
        assertThat(results.getFirst().getScore()).isBetween(0.0, 1.0);
    }
}
