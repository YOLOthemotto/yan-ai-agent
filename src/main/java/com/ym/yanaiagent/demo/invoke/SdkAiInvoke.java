package com.ym.yanaiagent.demo.invoke;// 建议dashscope SDK的版本 >= 2.12.0

import java.util.Arrays;
import java.lang.System;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.common.MultiModalMessage;

import java.util.Collections;
import java.util.List;

import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.alibaba.dashscope.utils.JsonUtils;
import com.alibaba.dashscope.utils.Constants;

public class SdkAiInvoke {
    static {
        Constants.baseHttpApiUrl = "https://llm-7gvp4yko2t9jg13c.cn-beijing.maas.aliyuncs.com/api/v1";
    }

    public static MultiModalConversationResult callWithMessage() throws ApiException, NoApiKeyException, InputRequiredException, UploadFileException {
        String apiKey = System.getenv("AI_DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Please set AI_DASHSCOPE_API_KEY environment variable");
        }

        MultiModalConversation conv = new MultiModalConversation();
        MultiModalMessage systemMsg = MultiModalMessage.builder()
                .role(Role.SYSTEM.getValue())
                .content(List.of(Collections.singletonMap("text", "You are a helpful assistant.")))
                .build();
        MultiModalMessage userMsg = MultiModalMessage.builder()
                .role(Role.USER.getValue())
                .content(List.of(Collections.singletonMap("text", "你是谁？")))
                .build();
        MultiModalConversationParam param = MultiModalConversationParam.builder()
                .apiKey(apiKey)
                // 此处以qwen3.8-flash为例，可按需更换模型名称。模型列表：https://help.aliyun.com/zh/model-studio/getting-started/models
                .model("qwen3.8-flash")
                .messages(Arrays.asList(systemMsg, userMsg))
                .build();
        return conv.call(param);
    }

    public static void main(String[] args) {
        try {
            MultiModalConversationResult result = callWithMessage();
            System.out.println(JsonUtils.toJson(result));
        } catch (ApiException | NoApiKeyException | InputRequiredException | UploadFileException e) {
            System.err.println("An error occurred while calling the generation service: " + e.getMessage());
        }
    }
}