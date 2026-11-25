package com.ym.yanaiagent.sessionmemory.service;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.session.EventQuery;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.SessionService;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.List;

/**
 * 对 Spring AI SessionService 的轻量封装，供业务层读取或删除历史记录。
 */
@Service
public class SessionHistoryService {

    private final SessionService sessionService;

    public SessionHistoryService(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * 读取完整事件日志中的聊天消息，适合页面展示。
     */
    public List<Message> getMessages(String sessionId) {
        Assert.hasText(sessionId, "sessionId must not be null or empty");
        return sessionService.getMessages(sessionId);
    }

    /**
     * 读取 Session 压缩后的当前上下文，即实际发给模型的消息视图。
     */
    public List<Message> getActiveMessages(String sessionId) {
        Assert.hasText(sessionId, "sessionId must not be null or empty");
        return sessionService.getActiveMessages(sessionId);
    }

    /**
     * 在完整历史中按关键词搜索事件。
     */
    public List<SessionEvent> search(String sessionId, String keyword) {
        Assert.hasText(sessionId, "sessionId must not be null or empty");
        Assert.hasText(keyword, "keyword must not be null or empty");
        return sessionService.searchEvents(sessionId, EventQuery.keyword(keyword));
    }

    /**
     * 删除 Session 元数据和全部历史。
     */
    public void delete(String sessionId) {
        Assert.hasText(sessionId, "sessionId must not be null or empty");
        sessionService.delete(sessionId);
    }
}
