package com.resume.assistant.controller;

import com.resume.assistant.model.ResumeDocument;
import com.resume.assistant.service.PdfExportService;
import com.resume.assistant.service.ResumeAgentService;
import com.resume.assistant.service.ResumeParserService;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 简历助手 REST API —— SSE 逐字流式输出
 */
@RestController
@RequestMapping("/api/resume")
public class ResumeController {

    private static final Logger log = LoggerFactory.getLogger(ResumeController.class);

    private final ResumeParserService parserService;
    private final ResumeAgentService agentService;
    private final PdfExportService pdfExportService;

    public ResumeController(ResumeParserService parserService,
                            ResumeAgentService agentService,
                            PdfExportService pdfExportService) {
        this.parserService = parserService;
        this.agentService = agentService;
        this.pdfExportService = pdfExportService;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "resume-assistant");
    }

    /**
     * 上传简历并流式诊断 —— 逐 token 推送
     */
    @PostMapping(value = "/upload", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter upload(@RequestParam("file") MultipartFile file) {
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);

        // 另起线程处理，避免阻塞 Tomcat 线程
        Thread.ofVirtual().start(() -> {
            try {
                sendEvent(emitter, "status", "📄 正在解析简历...");

                ResumeDocument resume = parserService.parse(file);

                // 先发送元信息（sessionId、文件名）
                sendEvent(emitter, "meta",
                        "{\"sessionId\":\"" + resume.getSessionId() + "\","
                                + "\"fileName\":\"" + escapeJson(resume.getFileName()) + "\"}");

                sendEvent(emitter, "status", "🔍 正在分析简历，生成诊断报告...");

                // 流式诊断：逐 token 推送到前端
                agentService.diagnoseStreaming(resume, new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String token) {
                        sendEvent(emitter, "token", token);
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse chatResponse) {
                        sendEvent(emitter, "done", "");
                        emitter.complete();
                        log.info("流式诊断完成: {}", resume.getFileName());
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error("流式诊断失败", error);
                        sendEvent(emitter, "error", error.getMessage());
                        emitter.complete();
                    }
                });

            } catch (Exception e) {
                log.error("简历处理失败", e);
                sendEvent(emitter, "error", e.getMessage());
                emitter.complete();
            }
        });

        return emitter;
    }

    /**
     * 流式对话优化 —— 逐 token 推送
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody Map<String, String> body) {
        String sessionId = body.get("sessionId");
        String message = body.get("message");

        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }

        SseEmitter emitter = new SseEmitter(3 * 60 * 1000L);

        Thread.ofVirtual().start(() -> {
            try {
                sendEvent(emitter, "status", "💬 正在思考...");

                agentService.chatStreaming(sessionId, message, new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String token) {
                        sendEvent(emitter, "token", token);
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse chatResponse) {
                        sendEvent(emitter, "done", "");
                        emitter.complete();
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error("流式对话失败 sessionId={}", sessionId, error);
                        sendEvent(emitter, "error", error.getMessage());
                        emitter.complete();
                    }
                });

            } catch (Exception e) {
                log.error("对话失败 sessionId={}", sessionId, e);
                sendEvent(emitter, "error", e.getMessage());
                emitter.complete();
            }
        });

        return emitter;
    }

    /**
     * 导出简历 Markdown（原始版）
     */
    @GetMapping("/export/{sessionId}")
    public ResponseEntity<byte[]> export(@PathVariable String sessionId) {
        String resumeText = agentService.getResumeText(sessionId);
        if (resumeText == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"resume.md\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(resumeText.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 导出优化版简历 —— 由 LLM 整合对话历史生成最终版
     */
    @GetMapping("/export/optimized/{sessionId}")
    public ResponseEntity<byte[]> exportOptimized(@PathVariable String sessionId,
                                                  @RequestParam(defaultValue = "tech") String template) {
        String optimized = agentService.generateOptimizedResume(sessionId, template);
        if (optimized == null || optimized.startsWith("⚠️")) {
            return ResponseEntity.badRequest().body(optimized.getBytes(StandardCharsets.UTF_8));
        }

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"resume-optimized.md\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(optimized.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 导出 PDF 版简历 —— 生成排版精美的 PDF 文件
     */
    @GetMapping("/export/pdf/{sessionId}")
    public ResponseEntity<byte[]> exportPdf(@PathVariable String sessionId,
                                            @RequestParam(defaultValue = "tech") String template) {
        String optimized = agentService.generateOptimizedResume(sessionId, template);
        if (optimized == null || optimized.startsWith("⚠️")) {
            return ResponseEntity.badRequest().build();
        }

        byte[] pdfBytes = pdfExportService.renderPdf(optimized, "个人简历");

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"resume.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }

    /**
     * 获取可用的简历模板列表
     */
    @GetMapping("/templates")
    public List<Map<String, String>> templates() {
        return agentService.getTemplates();
    }

    @GetMapping("/history/{sessionId}")
    public ResponseEntity<List<Map<String, Object>>> history(@PathVariable String sessionId) {
        var messages = agentService.getHistory(sessionId);
        if (messages == null || messages.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<Map<String, Object>> result = messages.stream()
                .map(m -> Map.<String, Object>of(
                        "role", m.type().name(),
                        "content", m.toString()
                ))
                .toList();

        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/session/{sessionId}")
    public Map<String, String> clearSession(@PathVariable String sessionId) {
        agentService.clearSession(sessionId);
        return Map.of("status", "ok", "message", "会话已清理");
    }

    // ── SSE 工具 ──

    private void sendEvent(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException e) {
            log.debug("SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
