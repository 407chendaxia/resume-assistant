package com.resume.assistant.service;

import com.resume.assistant.model.ResumeDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * 简历解析服务 —— 将 PDF / Word / Markdown 格式简历转为统一纯文本
 */
@Service
public class ResumeParserService {

    private static final Logger log = LoggerFactory.getLogger(ResumeParserService.class);
    private static final Set<String> SUPPORTED_FORMATS = Set.of("pdf", "docx", "md");

    /**
     * 解析上传的简历文件，返回 ResumeDocument
     */
    public ResumeDocument parse(MultipartFile file) throws IOException {
        String originalName = file.getOriginalFilename();
        String format = extractFormat(originalName);

        if (!SUPPORTED_FORMATS.contains(format)) {
            throw new IllegalArgumentException(
                    "不支持的简历格式: " + format + "，当前支持: " + SUPPORTED_FORMATS);
        }

        String rawText = switch (format) {
            case "pdf" -> parsePdf(file);
            case "docx" -> parseDocx(file);
            case "md" -> parseMarkdown(file);
            default -> throw new IllegalArgumentException("未知格式: " + format);
        };

        if (rawText.isBlank()) {
            throw new IOException("简历解析结果为空，请检查文件内容");
        }

        log.info("简历解析成功: {} ({} 字符)", originalName, rawText.length());

        return ResumeDocument.of(originalName, format, rawText,
                UUID.randomUUID().toString(), LocalDateTime.now());
    }

    // ── PDF ──
    private String parsePdf(MultipartFile file) throws IOException {
        try (var pdf = Loader.loadPDF(new RandomAccessReadBuffer(file.getBytes()))) {
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(pdf);
        }
    }

    // ── Word (.docx) ──
    private String parseDocx(MultipartFile file) throws IOException {
        try (var doc = new XWPFDocument(file.getInputStream());
             var extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }

    // ── Markdown ──
    private String parseMarkdown(MultipartFile file) throws IOException {
        return new String(file.getBytes(), StandardCharsets.UTF_8);
    }

    // ── 工具 ──
    private String extractFormat(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "unknown";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }
}
