package group.careerservice.service.RoadMapping;/* I love coding */

import group.careerservice.domain.dto.ExportReportRequestDTO;
import group.careerservice.domain.dto.ExportReportResponseDTO;
import group.careerservice.domain.dto.ReportDTO;
import group.careerservice.tools.PdfReportGenerator;
import group.tool.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 报告导出服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExportService {

    private final RoadMappingService roadMappingService;
    private final PdfReportGenerator pdfReportGenerator;

    /**
     * 报告导出文件存储路径
     * 默认存储在项目根目录下的 exports/career-reports 文件夹中
     */
    @Value("${career.report.export.path:./exports/career-reports}")
    private String exportBasePath;

    /**
     * 导出报告为PDF
     *
     * @param reportId 报告ID
     * @param request  导出请求
     * @return 导出响应
     */
    public ExportReportResponseDTO exportReportToPdf(String reportId, ExportReportRequestDTO request) {
        log.info("开始导出报告，报告ID: {}, 格式: {}", reportId, request.getFormat());

        String exportJobId = IdGenerator.generateShortId("cr_exp");

        try {
            // 1. 获取报告完整内容
            ReportDTO reportDTO = roadMappingService.queryReportStatus(reportId, 1);

            if (reportDTO == null) {
                log.error("报告不存在，报告ID: {}", reportId);
                return ExportReportResponseDTO.builder()
                        .exportJobId(exportJobId)
                        .status("failed")
                        .errorMessage("报告不存在")
                        .build();
            }

            // 2. 确保导出目录存在
            String outputPath = ensureExportDirectory();
            log.info("导出文件将保存到: {}", outputPath);

            // 3. 生成PDF
            String filePath = pdfReportGenerator.generateReport(reportDTO, request, outputPath);
            File pdfFile = new File(filePath);

            if (!pdfFile.exists()) {
                throw new IOException("PDF文件生成失败");
            }

            // 4. 构建响应
            String fileName = pdfFile.getName();
            String downloadUrl = buildDownloadUrl(fileName);

            log.info("报告导出成功，文件: {}, 大小: {} bytes", fileName, pdfFile.length());

            return ExportReportResponseDTO.builder()
                    .exportJobId(exportJobId)
                    .status("success")
                    .pollAfterMs(1500)
                    .downloadUrl(downloadUrl)
                    .filePath(filePath)
                    .fileName(fileName)
                    .build();

        } catch (Exception e) {
            log.error("报告导出失败，报告ID: {}, 错误: {}", reportId, e.getMessage(), e);
            return ExportReportResponseDTO.builder()
                    .exportJobId(exportJobId)
                    .status("failed")
                    .errorMessage("导出失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 确保导出目录存在，并返回完整路径
     *
     * @return 导出目录的绝对路径
     */
    private String ensureExportDirectory() throws IOException {
        // 解析路径
        Path path;
        if (exportBasePath.startsWith("./") || exportBasePath.startsWith(".\\")) {
            // 相对路径，基于项目根目录
            String userDir = System.getProperty("user.dir");
            path = Paths.get(userDir, exportBasePath.substring(2));
        } else if (exportBasePath.startsWith("/") || exportBasePath.contains(":")) {
            // 绝对路径
            path = Paths.get(exportBasePath);
        } else {
            // 默认相对路径
            String userDir = System.getProperty("user.dir");
            path = Paths.get(userDir, exportBasePath);
        }

        // 创建目录（如果不存在）
        if (!Files.exists(path)) {
            Files.createDirectories(path);
            log.info("创建导出目录: {}", path.toAbsolutePath());
        }

        return path.toAbsolutePath().toString();
    }

    /**
     * 构建下载URL
     *
     * @param fileName 文件名
     * @return 下载URL
     */
    private String buildDownloadUrl(String fileName) {
        // 这里可以根据实际部署情况调整URL格式
        // 例如: /api/career-reports/downloads/{fileName}
        return "/career-reports/downloads/" + fileName;
    }

    /**
     * 获取导出文件的完整路径
     *
     * @param fileName 文件名
     * @return 完整路径
     */
    public String getExportFilePath(String fileName) throws IOException {
        String exportDir = ensureExportDirectory();
        return Paths.get(exportDir, fileName).toString();
    }

    /**
     * 清理过期的导出文件（可选，可以配合定时任务使用）
     *
     * @param days 保留天数
     */
    public void cleanExpiredExports(int days) {
        try {
            String exportDir = ensureExportDirectory();
            File dir = new File(exportDir);
            File[] files = dir.listFiles();

            if (files == null) {
                return;
            }

            LocalDateTime cutoffDate = LocalDateTime.now().minusDays(days);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

            int deletedCount = 0;
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".pdf")) {
                    // 从文件名中提取时间戳
                    String fileName = file.getName();
                    int underscoreIndex = fileName.lastIndexOf('_');
                    int dotIndex = fileName.lastIndexOf('.');

                    if (underscoreIndex > 0 && dotIndex > underscoreIndex) {
                        String timestampStr = fileName.substring(underscoreIndex + 1, dotIndex);
                        try {
                            LocalDateTime fileTime = LocalDateTime.parse(timestampStr, formatter);
                            if (fileTime.isBefore(cutoffDate)) {
                                if (file.delete()) {
                                    deletedCount++;
                                    log.info("删除过期导出文件: {}", fileName);
                                }
                            }
                        } catch (Exception e) {
                            log.warn("无法解析文件名时间戳: {}", fileName);
                        }
                    }
                }
            }

            log.info("清理完成，共删除 {} 个过期文件", deletedCount);

        } catch (Exception e) {
            log.error("清理过期导出文件失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 导出报告为DOCX格式
     * 先导出PDF，然后转换为DOCX
     *
     * @param reportId 报告ID
     * @param request  导出请求
     * @return 导出响应
     */
    public ExportReportResponseDTO exportReportToDocx(String reportId, ExportReportRequestDTO request) {
        log.info("开始导出DOCX报告，报告ID: {}", reportId);

        String exportJobId = IdGenerator.generateShortId("cr_exp");

        try {
            // 1. 先获取报告内容并生成PDF
            ReportDTO reportDTO = roadMappingService.queryReportStatus(reportId, 1);

            if (reportDTO == null) {
                log.error("报告不存在，报告ID: {}", reportId);
                return ExportReportResponseDTO.builder()
                        .exportJobId(exportJobId)
                        .status("failed")
                        .errorMessage("报告不存在")
                        .build();
            }

            // 2. 确保导出目录存在
            String outputPath = ensureExportDirectory();

            // 3. 生成PDF文件
            String pdfFilePath = pdfReportGenerator.generateReport(reportDTO, request, outputPath);
            File pdfFile = new File(pdfFilePath);

            if (!pdfFile.exists()) {
                throw new IOException("PDF文件生成失败");
            }

            // 4. 将PDF转换为DOCX
            String docxFilePath = convertPdfToDocx(pdfFilePath, outputPath);
            File docxFile = new File(docxFilePath);

            // 5. 删除临时PDF文件
            pdfFile.delete();

            // 6. 构建响应
            String fileName = docxFile.getName();
            String downloadUrl = buildDownloadUrl(fileName);

            log.info("DOCX报告导出成功，文件: {}, 大小: {} bytes", fileName, docxFile.length());

            return ExportReportResponseDTO.builder()
                    .exportJobId(exportJobId)
                    .status("success")
                    .pollAfterMs(1500)
                    .downloadUrl(downloadUrl)
                    .filePath(docxFilePath)
                    .fileName(fileName)
                    .build();

        } catch (Exception e) {
            log.error("DOCX报告导出失败，报告ID: {}, 错误: {}", reportId, e.getMessage(), e);
            return ExportReportResponseDTO.builder()
                    .exportJobId(exportJobId)
                    .status("failed")
                    .errorMessage("导出失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 导出报告为Markdown格式
     * 先导出PDF，然后提取文本转换为Markdown
     *
     * @param reportId 报告ID
     * @param request  导出请求
     * @return 导出响应
     */
    public ExportReportResponseDTO exportReportToMarkdown(String reportId, ExportReportRequestDTO request) {
        log.info("开始导出Markdown报告，报告ID: {}", reportId);

        String exportJobId = IdGenerator.generateShortId("cr_exp");

        try {
            // 1. 先获取报告内容并生成PDF
            ReportDTO reportDTO = roadMappingService.queryReportStatus(reportId, 1);

            if (reportDTO == null) {
                log.error("报告不存在，报告ID: {}", reportId);
                return ExportReportResponseDTO.builder()
                        .exportJobId(exportJobId)
                        .status("failed")
                        .errorMessage("报告不存在")
                        .build();
            }

            // 2. 确保导出目录存在
            String outputPath = ensureExportDirectory();

            // 3. 生成PDF文件
            String pdfFilePath = pdfReportGenerator.generateReport(reportDTO, request, outputPath);
            File pdfFile = new File(pdfFilePath);

            if (!pdfFile.exists()) {
                throw new IOException("PDF文件生成失败");
            }

            // 4. 将PDF转换为Markdown
            String mdFilePath = convertPdfToMarkdown(pdfFilePath, outputPath, reportDTO);
            File mdFile = new File(mdFilePath);

            // 5. 删除临时PDF文件
            pdfFile.delete();

            // 6. 构建响应
            String fileName = mdFile.getName();
            String downloadUrl = buildDownloadUrl(fileName);

            log.info("Markdown报告导出成功，文件: {}, 大小: {} bytes", fileName, mdFile.length());

            return ExportReportResponseDTO.builder()
                    .exportJobId(exportJobId)
                    .status("success")
                    .pollAfterMs(1500)
                    .downloadUrl(downloadUrl)
                    .filePath(mdFilePath)
                    .fileName(fileName)
                    .build();

        } catch (Exception e) {
            log.error("Markdown报告导出失败，报告ID: {}, 错误: {}", reportId, e.getMessage(), e);
            return ExportReportResponseDTO.builder()
                    .exportJobId(exportJobId)
                    .status("failed")
                    .errorMessage("导出失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 将PDF文件转换为DOCX格式
     *
     * @param pdfFilePath PDF文件路径
     * @param outputPath  输出目录
     * @return DOCX文件路径
     */
    private String convertPdfToDocx(String pdfFilePath, String outputPath) throws IOException {
        // 读取PDF文本内容
        String pdfText = extractTextFromPdf(pdfFilePath);

        // 生成DOCX文件名
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String docxFileName = "career_report_" + timestamp + ".docx";
        String docxFilePath = Paths.get(outputPath, docxFileName).toString();

        // 创建DOCX文档
        try (XWPFDocument document = new XWPFDocument()) {
            // 添加标题
            XWPFParagraph titleParagraph = document.createParagraph();
            titleParagraph.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = titleParagraph.createRun();
            titleRun.setText("职业生涯规划报告");
            titleRun.setBold(true);
            titleRun.setFontSize(18);

            // 添加空行
            document.createParagraph();

            // 将PDF文本按段落分割并添加到文档
            String[] paragraphs = pdfText.split("\n\n");
            for (String paragraph : paragraphs) {
                if (paragraph.trim().isEmpty()) continue;

                XWPFParagraph para = document.createParagraph();
                para.setAlignment(ParagraphAlignment.LEFT);
                XWPFRun run = para.createRun();
                run.setText(paragraph.trim());
                run.setFontSize(12);
            }

            // 保存文档
            try (FileOutputStream out = new FileOutputStream(docxFilePath)) {
                document.write(out);
            }
        }

        log.info("PDF转换为DOCX成功: {}", docxFilePath);
        return docxFilePath;
    }

    /**
     * 将PDF文件转换为Markdown格式
     *
     * @param pdfFilePath PDF文件路径
     * @param outputPath  输出目录
     * @param reportDTO   报告数据
     * @return Markdown文件路径
     */
    private String convertPdfToMarkdown(String pdfFilePath, String outputPath, ReportDTO reportDTO) throws IOException {
        // 读取PDF文本内容
        String pdfText = extractTextFromPdf(pdfFilePath);

        // 生成Markdown文件名
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String mdFileName = "career_report_" + timestamp + ".md";
        String mdFilePath = Paths.get(outputPath, mdFileName).toString();

        // 构建Markdown内容
        StringBuilder mdContent = new StringBuilder();

        // 添加标题
        mdContent.append("# 职业生涯规划报告\n\n");

        // 添加报告基本信息
        if (reportDTO.getReportTitle() != null) {
            mdContent.append("**报告标题：** ").append(reportDTO.getReportTitle()).append("\n\n");
        }


        mdContent.append("---\n\n");

        // 添加PDF内容
        String[] paragraphs = pdfText.split("\n\n");
        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) continue;

            // 根据内容判断是否为标题
            if (trimmed.length() < 50 && !trimmed.contains("。") && !trimmed.contains("，")) {
                mdContent.append("## ").append(trimmed).append("\n\n");
            } else {
                mdContent.append(trimmed).append("\n\n");
            }
        }

        // 写入文件
        try (FileWriter writer = new FileWriter(mdFilePath)) {
            writer.write(mdContent.toString());
        }

        log.info("PDF转换为Markdown成功: {}", mdFilePath);
        return mdFilePath;
    }

    /**
     * 从PDF文件中提取文本
     *
     * @param pdfFilePath PDF文件路径
     * @return 提取的文本内容
     */
    private String extractTextFromPdf(String pdfFilePath) throws IOException {
        try (PDDocument document = PDDocument.load(new File(pdfFilePath))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }
}
