package com.resume.assistant.model;

import java.time.LocalDateTime;

/**
 * 简历文档实体 —— 解析后的简历在系统内的统一表示
 */
public class ResumeDocument {

    private String fileName;
    private String fileFormat;
    private String rawText;
    private String sessionId;
    private LocalDateTime parsedAt;

    public ResumeDocument() {
    }

    public ResumeDocument(String fileName, String fileFormat, String rawText,
                          String sessionId, LocalDateTime parsedAt) {
        this.fileName = fileName;
        this.fileFormat = fileFormat;
        this.rawText = rawText;
        this.sessionId = sessionId;
        this.parsedAt = parsedAt;
    }

    // ── Getters & Setters ──

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFileFormat() { return fileFormat; }
    public void setFileFormat(String fileFormat) { this.fileFormat = fileFormat; }

    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public LocalDateTime getParsedAt() { return parsedAt; }
    public void setParsedAt(LocalDateTime parsedAt) { this.parsedAt = parsedAt; }

    // ── Builder-style factory ──

    public static ResumeDocument of(String fileName, String fileFormat, String rawText,
                                    String sessionId, LocalDateTime parsedAt) {
        return new ResumeDocument(fileName, fileFormat, rawText, sessionId, parsedAt);
    }
}
