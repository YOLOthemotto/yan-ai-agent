package com.ym.yanaiagent.app;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LoveAppTest {

    @Resource
    private LoveApp loveApp;

    @Test
    void keepsMessagesInTheSameSession() {
        String sessionId = UUID.randomUUID().toString();

        String firstAnswer = loveApp.doChat("我叫小明，请记住。", sessionId);
        String secondAnswer = loveApp.doChat("我叫什么名字？只回答名字。", sessionId);

        System.out.println("firstAnswer = " + firstAnswer);
        System.out.println("secondAnswer = " + secondAnswer);
        assertThat(secondAnswer).contains("小明");
    }
}
