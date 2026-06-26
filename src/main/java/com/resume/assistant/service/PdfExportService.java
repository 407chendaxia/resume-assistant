package com.resume.assistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import java.io.ByteArrayOutputStream;

/**
 * PDF 导出服务 —— 将 Markdown 简历转换为排版精美的 PDF
 * <p>
 * 流程：Markdown 文本 → HTML 模板 → PDF（通过 openhtmltopdf）
 */
@Service
public class PdfExportService {

    private static final Logger log = LoggerFactory.getLogger(PdfExportService.class);

    private static final String HTML_TEMPLATE = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="UTF-8">
            <title>%s</title>
            <style>
                @page {
                    size: A4;
                    margin: 2cm 2.2cm;
                    @bottom-center {
                        content: "第 " counter(page) " 页";
                        font-size: 9pt;
                        color: #999;
                        font-family: "Microsoft YaHei", "SimHei", sans-serif;
                    }
                }
                body {
                    font-family: "Microsoft YaHei", "SimHei", "PingFang SC", sans-serif;
                    font-size: 11pt;
                    line-height: 1.7;
                    color: #333;
                }
                h1 {
                    text-align: center;
                    font-size: 20pt;
                    color: #1a1a2e;
                    border-bottom: 2px solid #4f46e5;
                    padding-bottom: 12px;
                    margin-bottom: 8px;
                }
                h2 {
                    font-size: 14pt;
                    color: #4f46e5;
                    border-left: 4px solid #4f46e5;
                    padding-left: 10px;
                    margin-top: 24px;
                    margin-bottom: 10px;
                }
                h3 {
                    font-size: 12pt;
                    color: #333;
                    margin-top: 14px;
                    margin-bottom: 6px;
                }
                p { margin: 4px 0; }
                ul, ol { padding-left: 22px; margin: 4px 0; }
                li { margin: 2px 0; }
                strong { color: #1a1a2e; }
                table {
                    width: 100%%;
                    border-collapse: collapse;
                    margin: 8px 0;
                    font-size: 10pt;
                }
                th {
                    background: #4f46e5;
                    color: #fff;
                    padding: 6px 10px;
                    text-align: left;
                }
                td {
                    padding: 5px 10px;
                    border-bottom: 1px solid #e2e8f0;
                }
                blockquote {
                    border-left: 3px solid #cbd5e1;
                    padding-left: 12px;
                    color: #64748b;
                    margin: 8px 0;
                }
                .contact {
                    text-align: center;
                    font-size: 10pt;
                    color: #666;
                    margin-bottom: 16px;
                }
                hr {
                    border: none;
                    border-top: 1px solid #e2e8f0;
                    margin: 16px 0;
                }
            </style>
            </head>
            <body>
            %s
            </body>
            </html>
            """;

    /**
     * 将简历内容（Markdown）渲染为 PDF 字节流
     */
    public byte[] renderPdf(String markdownContent, String title) {
        String html = buildHtml(markdownContent, title);

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, null);
            builder.toStream(os);
            builder.run();

            byte[] pdfBytes = os.toByteArray();
            log.info("PDF 生成成功，大小={}KB", pdfBytes.length / 1024);
            return pdfBytes;

        } catch (Exception e) {
            log.error("PDF 生成失败", e);
            throw new RuntimeException("PDF 生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构建专业排版的 HTML 简历模板
     */
    private String buildHtml(String markdown, String title) {
        String bodyHtml = simpleMarkdownToHtml(markdown);
        return HTML_TEMPLATE.formatted(title, bodyHtml);
    }

    /**
     * 简单的 Markdown → HTML 转换（覆盖简历常用语法）
     */
    private String simpleMarkdownToHtml(String md) {
        return md
                // 标题
                .replaceAll("(?m)^### (.+)$", "<h3>$1</h3>")
                .replaceAll("(?m)^## (.+)$", "<h2>$1</h2>")
                .replaceAll("(?m)^# (.+)$", "<h1>$1</h1>")
                // 粗体
                .replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>")
                // 列表
                .replaceAll("(?m)^- (.+)$", "<li>$1</li>")
                .replaceAll("(?m)^\\d+\\. (.+)$", "<li>$1</li>")
                // 分割线
                .replaceAll("(?m)^---$", "<hr>")
                // 段落
                .replaceAll("\n\n", "\n<p>&nbsp;</p>\n")
                // 换行
                .replaceAll("\n", "<br>\n");
    }
}
