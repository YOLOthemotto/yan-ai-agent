package com.ym.yanaiagent.app;

import com.ym.yanaiagent.rag.RagService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LoveAppTest {

    @Resource
    private LoveApp loveApp;

    @Resource
    private RagService ragService;

    @Test
    void doChatWithRag() {
        ragService.addDocuments(List.of(
                new Document("10000000-0000-0000-0000-000000000001",
                        """
                        日常陪伴：情侣下班后各自刷手机、缺少交流时，可以尝试本示例知识库自定义的“晚安十分钟”练习：
                        睡前双方自愿放下手机十分钟，轮流分享今天一件开心的事和一件需要支持的事。
                        倾听者先复述对方的感受，再询问对方希望被倾听还是一起想办法。时间安排由双方共同商量。
                        """,
                        Map.of("domain", "love", "source", "love-rag-demo", "topic", "companionship")),
                new Document("10000000-0000-0000-0000-000000000002",
                        """
                        争吵后的沟通：本示例知识库自定义的“三句修复卡”包括：
                        第一句描述具体发生的事情，不使用“你总是”一类指责；
                        第二句表达自己的感受和需要；第三句提出一个具体、可协商的小请求。
                        情绪激动时可以先协商暂停，约好继续沟通的时间，避免冷暴力。
                        """,
                        Map.of("domain", "love", "source", "love-rag-demo", "topic", "communication")),
                new Document("10000000-0000-0000-0000-000000000003",
                        """
                        异地恋的联系：双方可以一起约定适合各自作息的通话时间。
                        忙碌时提前说明，不把回复速度直接等同于爱不爱。
                        尊重隐私，不要求共享密码或实时定位，见面和联系频率都应由双方自愿协商。
                        """,
                        Map.of("domain", "love", "source", "love-rag-demo", "topic", "long-distance"))));

        String question = "我和女朋友下班后总是各刷各的手机，越来越少交流。"
                + "知识库里推荐的陪伴练习叫什么？请保留练习原名，简短说明怎么做。";
        String answer = loveApp.doChatWithRag(question, UUID.randomUUID().toString());

        System.out.println("RAG 问题：" + question);
        System.out.println("RAG 回答：" + answer);
        assertThat(answer).contains("晚安十分钟");
    }

    @Test
    void keepsMessagesInTheSameSession() {
        String sessionId = UUID.randomUUID().toString();

        String firstAnswer = loveApp.doChat("我叫小明，请记住。", sessionId);
        String secondAnswer = loveApp.doChat("我叫什么名字？只回答名字。", sessionId);

        System.out.println("firstAnswer = " + firstAnswer);
        System.out.println("secondAnswer = " + secondAnswer);
        assertThat(secondAnswer).contains("小明");
    }

    @Test
    void doChatWithReport() {
        String sessionId = UUID.randomUUID().toString();
        String message = "我和女朋友最近总是吵架，她说我不够关心她，我该怎么办？";
        LoveApp.LoveReport loveReport = loveApp.doChatWithReport(message, sessionId);
        assertThat(loveReport).isNotNull();
    }
}
