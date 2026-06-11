package com.resume.assistant.service;

import com.resume.assistant.model.ResumeDocument;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简历优化 Agent 服务 —— 核心对话引擎
 * <p>
 * 支持两种模式：
 * - 同步：适合简单调用和诊断
 * - 流式：逐 token 输出，适合前端打字机效果
 */
@Service
public class ResumeAgentService {

    private static final Logger log = LoggerFactory.getLogger(ResumeAgentService.class);

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final Map<String, ChatMemory> sessionMemoryMap = new ConcurrentHashMap<>();
    private final Map<String, String> sessionResumeMap = new ConcurrentHashMap<>();

    @Autowired
    public ResumeAgentService(ChatModel chatModel, StreamingChatModel streamingChatModel) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
    }

    // ══════════════════════════════════════
    //  同步 API（降级/内部使用）
    // ══════════════════════════════════════

    /**
     * 同步诊断（无流式需求时使用）
     */
    public String diagnose(ResumeDocument resume) {
        String sessionId = resume.getSessionId();
        sessionResumeMap.put(sessionId, resume.getRawText());

        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(20);
        memory.add(SystemMessage.from(ResumePromptTemplates.SYSTEM_PROMPT));
        sessionMemoryMap.put(sessionId, memory);

        String diagnosisPrompt = ResumePromptTemplates.DIAGNOSIS_TEMPLATE
                .replace("{resumeText}", resume.getRawText());
        memory.add(UserMessage.from(diagnosisPrompt));

        String response = chatModel.chat(diagnosisPrompt);
        memory.add(AiMessage.from(response));

        log.info("诊断完成 sessionId={}, 回复长度={}", sessionId, response.length());
        return response;
    }

    /**
     * 同步对话
     */
    public String chat(String sessionId, String userMessage) {
        ChatMemory memory = sessionMemoryMap.get(sessionId);
        if (memory == null) {
            return "⚠️ 会话已过期或不存在，请重新上传简历。";
        }

        String resumeText = sessionResumeMap.getOrDefault(sessionId, "");
        String contextualMessage = buildContextualMessage(userMessage, resumeText);

        memory.add(UserMessage.from(contextualMessage));
        String response = chatModel.chat(contextualMessage);
        memory.add(AiMessage.from(response));

        log.info("对话完成 sessionId={}, 回复长度={}", sessionId, response.length());
        return response;
    }

    // ══════════════════════════════════════
    //  流式 API（逐 token 推送）
    // ══════════════════════════════════════

    /**
     * 流式诊断 —— 逐 token 回调 onNext，完成时回调 onComplete
     */
    public void diagnoseStreaming(ResumeDocument resume, StreamingChatResponseHandler handler) {
        String sessionId = resume.getSessionId();
        sessionResumeMap.put(sessionId, resume.getRawText());

        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(20);
        memory.add(SystemMessage.from(ResumePromptTemplates.SYSTEM_PROMPT));
        sessionMemoryMap.put(sessionId, memory);

        String diagnosisPrompt = ResumePromptTemplates.DIAGNOSIS_TEMPLATE
                .replace("{resumeText}", resume.getRawText());
        memory.add(UserMessage.from(diagnosisPrompt));

        List<ChatMessage> messages = memory.messages();
        ChatRequest request = ChatRequest.builder().messages(messages).build();

        // 用 StringBuilder 收集完整回复，在 onComplete 时存入 memory
        StringBuilder fullResponse = new StringBuilder();

        streamingChatModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                fullResponse.append(token);
                handler.onPartialResponse(token);
            }

            @Override
            public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse chatResponse) {
                memory.add(AiMessage.from(fullResponse.toString()));
                handler.onCompleteResponse(chatResponse);
            }

            @Override
            public void onError(Throwable error) {
                log.error("流式诊断失败 sessionId={}", sessionId, error);
                handler.onError(error);
            }
        });

        log.info("流式诊断已发起 sessionId={}", sessionId);
    }

    /**
     * 流式对话 —— 逐 token 回调
     */
    public void chatStreaming(String sessionId, String userMessage, StreamingChatResponseHandler handler) {
        ChatMemory memory = sessionMemoryMap.get(sessionId);
        if (memory == null) {
            handler.onError(new IllegalStateException("会话已过期或不存在，请重新上传简历。"));
            return;
        }

        String resumeText = sessionResumeMap.getOrDefault(sessionId, "");
        String contextualMessage = buildContextualMessage(userMessage, resumeText);
        memory.add(UserMessage.from(contextualMessage));

        List<ChatMessage> messages = memory.messages();
        ChatRequest request = ChatRequest.builder().messages(messages).build();

        StringBuilder fullResponse = new StringBuilder();

        streamingChatModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
                fullResponse.append(token);
                handler.onPartialResponse(token);
            }

            @Override
            public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse chatResponse) {
                memory.add(AiMessage.from(fullResponse.toString()));
                handler.onCompleteResponse(chatResponse);
            }

            @Override
            public void onError(Throwable error) {
                log.error("流式对话失败 sessionId={}", sessionId, error);
                handler.onError(error);
            }
        });

        log.info("流式对话已发起 sessionId={}", sessionId);
    }

    // ══════════════════════════════════════
    //  公共辅助
    // ══════════════════════════════════════

    public String getResumeText(String sessionId) {
        return sessionResumeMap.get(sessionId);
    }

    public List<ChatMessage> getHistory(String sessionId) {
        ChatMemory memory = sessionMemoryMap.get(sessionId);
        if (memory == null) return List.of();
        List<ChatMessage> all = memory.messages();
        return all.size() > 1 ? all.subList(1, all.size()) : List.of();
    }

    // ══════════════════════════════════════
    //  优化版导出
    // ══════════════════════════════════════

    /**
     * 生成最终优化版简历 —— 整合对话中所有优化共识
     */
    public String generateOptimizedResume(String sessionId, String templateKey) {
        ChatMemory memory = sessionMemoryMap.get(sessionId);
        String originalResume = sessionResumeMap.get(sessionId);

        if (memory == null || originalResume == null) {
            return "⚠️ 会话不存在或简历未上传。";
        }

        // 提取对话历史（跳过 SystemMessage）
        List<ChatMessage> history = memory.messages();
        StringBuilder historyStr = new StringBuilder();
        for (int i = 1; i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            String role = msg instanceof dev.langchain4j.data.message.UserMessage ? "用户" : "AI顾问";
            historyStr.append("【").append(role).append("】\n")
                    .append(msg.toString()).append("\n\n");
        }

        String style = switch (templateKey) {
            case "tech" -> ResumePromptTemplates.STYLE_TECH;
            case "product" -> ResumePromptTemplates.STYLE_PRODUCT;
            case "entry" -> ResumePromptTemplates.STYLE_ENTRY;
            default -> ResumePromptTemplates.STYLE_TECH;
        };

        String prompt = ResumePromptTemplates.FINAL_RESUME_PROMPT
                .replace("{originalResume}", originalResume)
                .replace("{conversationHistory}", historyStr.toString())
                .replace("{templateStyle}", style);

        String result = chatModel.chat(prompt);
        log.info("优化版简历生成完成 sessionId={}, 长度={}", sessionId, result.length());
        return result;
    }

    // ══════════════════════════════════════
    //  模板
    // ══════════════════════════════════════

    public List<Map<String, String>> getTemplates() {
        return List.of(
                Map.of("key", "tech", "name", "技术研发岗",
                        "desc", "突出技术深度、项目成果、性能指标"),
                Map.of("key", "product", "name", "产品/管理岗",
                        "desc", "突出业务影响、团队管理、战略思维"),
                Map.of("key", "entry", "name", "应届生/初级岗",
                        "desc", "突出学习能力、实习实践、专业基础")
        );
    }

    public void clearSession(String sessionId) {
        sessionMemoryMap.remove(sessionId);
        sessionResumeMap.remove(sessionId);
        log.info("会话清理完成: {}", sessionId);
    }

    private String buildContextualMessage(String userMessage, String resumeText) {
        if (resumeText.isEmpty()) return userMessage;

        String truncatedResume = resumeText.length() > 3000
                ? resumeText.substring(0, 3000) + "\n...(简历内容过长，已截断)"
                : resumeText;

        return String.format("""
                【当前简历全文供参考】
                %s
                
                【用户指令】
                %s
                """, truncatedResume, userMessage);
    }
}
