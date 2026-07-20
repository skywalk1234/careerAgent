package group.careerservice.tools;/* I love coding */

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.*;
import group.careerservice.domain.dto.ExportReportRequestDTO;
import group.careerservice.domain.dto.ReportDTO;
import group.careerservice.domain.dto.RoadMappingDTO.*;
import lombok.extern.slf4j.Slf4j;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.data.category.DefaultCategoryDataset;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * PDF报告生成工具类 - 支持中文
 */
@Slf4j
@Component
public class PdfReportGenerator {

    // 颜色定义
    private static final Color PRIMARY_COLOR = new Color(41, 98, 255);
    private static final Color SECONDARY_COLOR = new Color(0, 150, 136);
    private static final Color ACCENT_COLOR = new Color(255, 112, 67);
    private static final Color SUCCESS_COLOR = new Color(76, 175, 80);
    private static final Color TEXT_DARK = new Color(33, 37, 41);
    private static final Color TEXT_LIGHT = new Color(108, 117, 125);
    private static final Color BG_LIGHT = new Color(248, 249, 250);
    private static final Color BORDER_COLOR = new Color(222, 226, 230);

    // 中文字体
    private BaseFont chineseBaseFont;
    private Font chineseFontNormal;
    private Font chineseFontBold;
    private Font chineseFontSmall;
    private Font chineseFontLarge;
    private Font chineseFontTitle;

    /**
     * 初始化中文字体
     */
    private void initChineseFonts() {
        try {
            // 尝试加载系统字体或内嵌字体
            chineseBaseFont = loadChineseFont();

            // 创建不同大小的中文字体
            chineseFontNormal = new Font(chineseBaseFont, 12, Font.NORMAL, TEXT_DARK);
            chineseFontBold = new Font(chineseBaseFont, 12, Font.BOLD, TEXT_DARK);
            chineseFontSmall = new Font(chineseBaseFont, 10, Font.NORMAL, TEXT_LIGHT);
            chineseFontLarge = new Font(chineseBaseFont, 14, Font.NORMAL, TEXT_DARK);
            chineseFontTitle = new Font(chineseBaseFont, 18, Font.BOLD, PRIMARY_COLOR);

        } catch (Exception e) {
            log.error("加载中文字体失败，使用默认字体", e);
            // 降级处理：使用默认字体
            chineseFontNormal = new Font(Font.HELVETICA, 12, Font.NORMAL, TEXT_DARK);
            chineseFontBold = new Font(Font.HELVETICA, 12, Font.BOLD, TEXT_DARK);
            chineseFontSmall = new Font(Font.HELVETICA, 10, Font.NORMAL, TEXT_LIGHT);
            chineseFontLarge = new Font(Font.HELVETICA, 14, Font.NORMAL, TEXT_DARK);
            chineseFontTitle = new Font(Font.HELVETICA, 18, Font.BOLD, PRIMARY_COLOR);
        }
    }

    /**
     * 加载中文字体
     * 优先顺序：1. 内嵌字体 2. Windows系统字体 3. Linux系统字体
     */
    private BaseFont loadChineseFont() throws IOException, DocumentException {
        // 1. 尝试加载内嵌字体（从resources目录）
        try {
            ClassPathResource resource = new ClassPathResource("fonts/simsun.ttc");
            if (resource.exists()) {
                log.info("加载内嵌字体: simsun.ttc");
                return BaseFont.createFont(resource.getURL().toString() + ",0", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            }
        } catch (Exception e) {
            log.warn("无法加载内嵌字体", e);
        }

        // 2. 尝试Windows系统字体
        String[] windowsFonts = {
                "C:/Windows/Fonts/simsun.ttc",
                "C:/Windows/Fonts/simhei.ttf",
                "C:/Windows/Fonts/msyh.ttc",
                "C:/Windows/Fonts/msyhbd.ttc"
        };

        for (String fontPath : windowsFonts) {
            File fontFile = new File(fontPath);
            if (fontFile.exists()) {
                log.info("加载Windows系统字体: {}", fontPath);
                if (fontPath.endsWith(".ttc")) {
                    return BaseFont.createFont(fontPath + ",0", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                } else {
                    return BaseFont.createFont(fontPath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                }
            }
        }

        // 3. 尝试Linux/Mac系统字体
        String[] linuxFonts = {
                "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
                "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
                "/System/Library/Fonts/PingFang.ttc",
                "/System/Library/Fonts/STHeiti Light.ttc"
        };

        for (String fontPath : linuxFonts) {
            File fontFile = new File(fontPath);
            if (fontFile.exists()) {
                log.info("加载Linux/Mac系统字体: {}", fontPath);
                if (fontPath.endsWith(".ttc")) {
                    return BaseFont.createFont(fontPath + ",0", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                } else {
                    return BaseFont.createFont(fontPath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                }
            }
        }

        // 4. 使用OpenPDF内置的亚洲字体（如果可用）
        try {
            return BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            log.warn("无法加载STSong-Light字体", e);
        }

        // 如果都失败，抛出异常
        throw new IOException("未找到可用的中文字体");
    }

    /**
     * 生成PDF报告
     */
    public String generateReport(ReportDTO reportDTO, ExportReportRequestDTO config, String outputPath)
            throws IOException, DocumentException {
        log.info("开始生成PDF报告，报告ID: {}", reportDTO.getReportId());

        // 初始化字体
        initChineseFonts();

        // 创建文档
        Document document = new Document(PageSize.A4, 50, 50, 60, 60);

        String fileName = generateFileName(reportDTO.getReportTitle());
        String fullPath = outputPath + File.separator + fileName;
        File outputFile = new File(fullPath);
        outputFile.getParentFile().mkdirs();

        PdfWriter writer = PdfWriter.getInstance(document, new FileOutputStream(outputFile));

        // 设置页眉页脚
        if (config.getPdfTemplate() != null) {
            writer.setPageEvent(new ReportPageEventHelper(config.getPdfTemplate(), chineseBaseFont));
        }

        document.open();

        // 添加封面
        if (Boolean.TRUE.equals(config.getIncludeCover())) {
            addCoverPage(document, reportDTO, config);
            document.newPage();
        }

        // 添加报告内容
        addReportContent(document, reportDTO, config);

        document.close();
        writer.close();

        log.info("PDF报告生成完成，文件路径: {}", fullPath);
        return fullPath;
    }

    /**
     * 生成文件名
     */
    private String generateFileName(String reportTitle) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String safeTitle = reportTitle.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9]", "_");
        if (safeTitle.length() > 50) {
            safeTitle = safeTitle.substring(0, 50);
        }
        return String.format("%s_%s.pdf", safeTitle, timestamp);
    }

    /**
     * 添加封面页
     */
    private void addCoverPage(Document document, ReportDTO reportDTO, ExportReportRequestDTO config)
            throws DocumentException {

        ExportReportRequestDTO.CoverConfig coverConfig = config.getPdfTemplate().getCover();

        // 顶部装饰条
        PdfPTable topBar = new PdfPTable(1);
        topBar.setWidthPercentage(100);
        PdfPCell barCell = new PdfPCell();
        barCell.setFixedHeight(8);
        barCell.setBackgroundColor(PRIMARY_COLOR);
        barCell.setBorder(Rectangle.NO_BORDER);
        topBar.addCell(barCell);
        document.add(topBar);

        addEmptyLines(document, 6);

        // 主标题
        Font coverTitleFont = new Font(chineseBaseFont, 32, Font.BOLD, PRIMARY_COLOR);
        Paragraph title = new Paragraph(coverConfig.getTitle(), coverTitleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        document.add(title);

        // 副标题
        Font coverSubtitleFont = new Font(chineseBaseFont, 16, Font.NORMAL, TEXT_LIGHT);
        Paragraph subtitle = new Paragraph(coverConfig.getSubtitle(), coverSubtitleFont);
        subtitle.setAlignment(Element.ALIGN_CENTER);
        subtitle.setSpacingBefore(15);
        document.add(subtitle);

        addEmptyLines(document, 5);

        // 报告信息卡片
        PdfPTable infoCard = new PdfPTable(1);
        infoCard.setWidthPercentage(75);
        infoCard.setHorizontalAlignment(Element.ALIGN_CENTER);

        // 报告标题
        Font reportTitleFont = new Font(chineseBaseFont, 14, Font.BOLD, TEXT_DARK);
        PdfPCell titleCell = createCardCell(reportDTO.getReportTitle(), reportTitleFont, Element.ALIGN_CENTER);
        titleCell.setPadding(12);
        infoCard.addCell(titleCell);

        // 路径信息
        if (reportDTO.getPathRef() != null) {
            Font pathFont = new Font(chineseBaseFont, 11, Font.NORMAL, TEXT_LIGHT);
            String pathInfo = String.format("路径: %s → %s",
                    reportDTO.getPathRef().getPathName(),
                    reportDTO.getPathRef().getTargetJobName());
            PdfPCell pathCell = createCardCell(pathInfo, pathFont, Element.ALIGN_CENTER);
            pathCell.setPadding(8);
            infoCard.addCell(pathCell);
        }

        // 学生信息
        if (reportDTO.getProfileSnapshot() != null) {
            Font profileFont = new Font(chineseBaseFont, 10, Font.NORMAL, TEXT_LIGHT);
            ProfileSnapshot profile = reportDTO.getProfileSnapshot();
            String profileInfo = String.format("姓名: %s    专业: %s    城市: %s",
                    profile.getName(), profile.getMajor(), profile.getCity());
            PdfPCell profileCell = createCardCell(profileInfo, profileFont, Element.ALIGN_CENTER);
            profileCell.setPadding(8);
            infoCard.addCell(profileCell);
        }

        document.add(infoCard);

        addEmptyLines(document, 6);

        // 生成时间
        if (Boolean.TRUE.equals(config.getIncludeTimestamp())) {
            Font timeFont = new Font(chineseBaseFont, 10, Font.NORMAL, TEXT_LIGHT);
            String timeStr = "生成时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm"));
            Paragraph timePara = new Paragraph(timeStr, timeFont);
            timePara.setAlignment(Element.ALIGN_CENTER);
            document.add(timePara);
        }

        // 底部装饰条
        addEmptyLines(document, 3);
        PdfPTable bottomBar = new PdfPTable(1);
        bottomBar.setWidthPercentage(100);
        PdfPCell bottomBarCell = new PdfPCell();
        bottomBarCell.setFixedHeight(4);
        bottomBarCell.setBackgroundColor(SECONDARY_COLOR);
        bottomBarCell.setBorder(Rectangle.NO_BORDER);
        bottomBar.addCell(bottomBarCell);
        document.add(bottomBar);
    }

    private PdfPCell createCardCell(String text, Font font, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setBackgroundColor(BG_LIGHT);
        cell.setBorderColor(BORDER_COLOR);
        cell.setBorderWidth(1);
        cell.setPadding(8);
        return cell;
    }

    /**
     * 添加报告内容
     */
    private void addReportContent(Document document, ReportDTO reportDTO, ExportReportRequestDTO config)
            throws DocumentException, IOException {

        // 1. 执行摘要
        if (reportDTO.getReportSections() != null && reportDTO.getReportSections().getExecutiveSummary() != null) {
            addSectionTitle(document, "一、执行摘要");
            ReportSections.ExecutiveSummary summary = reportDTO.getReportSections().getExecutiveSummary();
            addChineseParagraph(document, summary.getContent(), chineseFontNormal);
            addEmptyLines(document, 1);
        }

        // 2. 现状评估
        if (reportDTO.getReportSections() != null && reportDTO.getReportSections().getCurrentAssessment() != null) {
            addSectionTitle(document, "二、现状评估");

            // 添加能力对比图表
            if (reportDTO.getEvaluationSnapshot() != null &&
                    reportDTO.getEvaluationSnapshot().getAbilityComparison() != null) {
                addAbilityComparisonChart(document, reportDTO.getEvaluationSnapshot().getAbilityComparison());
            }

            ReportSections.CurrentAssessment assessment = reportDTO.getReportSections().getCurrentAssessment();
            addChineseParagraph(document, assessment.getContent(), chineseFontNormal);
            addEmptyLines(document, 1);
        }

        // 3. 目标岗位分析
        if (reportDTO.getReportSections() != null && reportDTO.getReportSections().getTargetAnalysis() != null) {
            addSectionTitle(document, "三、目标岗位分析");
            ReportSections.TargetAnalysis analysis = reportDTO.getReportSections().getTargetAnalysis();
            addChineseParagraph(document, analysis.getContent(), chineseFontNormal);
            addEmptyLines(document, 1);
        }

        // 4. 路径策略
        if (reportDTO.getReportSections() != null && reportDTO.getReportSections().getPathStrategy() != null) {
            addSectionTitle(document, "四、路径策略");
            ReportSections.PathStrategy strategy = reportDTO.getReportSections().getPathStrategy();
            addChineseParagraph(document, strategy.getContent(), chineseFontNormal);
            addEmptyLines(document, 1);
        }

        // 5. 阶段计划
        if (reportDTO.getReportSections() != null && reportDTO.getReportSections().getStagePlan() != null) {
            addSectionTitle(document, "五、阶段计划");
            addStagePlanSection(document, reportDTO.getReportSections().getStagePlan());
            addEmptyLines(document, 1);
        }

        // 6. 风险与对策
        if (reportDTO.getReportSections() != null && reportDTO.getReportSections().getRiskControl() != null) {
            addSectionTitle(document, "六、风险与对策");
            addRiskControlSection(document, reportDTO.getReportSections().getRiskControl());
            addEmptyLines(document, 1);
        }

        // 7. 资源建议
        if (reportDTO.getReportSections() != null && reportDTO.getReportSections().getResourceRecommendations() != null) {
            addSectionTitle(document, "七、资源建议");
            addResourceRecommendationsSection(document, reportDTO.getReportSections().getResourceRecommendations());
            addEmptyLines(document, 1);
        }

        // 8. 复盘机制
        if (reportDTO.getReportSections() != null && reportDTO.getReportSections().getReviewMechanism() != null) {
            addSectionTitle(document, "八、复盘机制");
            addReviewMechanismSection(document, reportDTO.getReportSections().getReviewMechanism());
        }
    }

    private void addSectionTitle(Document document, String title) throws DocumentException {
        Font sectionFont = new Font(chineseBaseFont, 16, Font.BOLD, PRIMARY_COLOR);
        Paragraph paragraph = new Paragraph(title, sectionFont);
        paragraph.setSpacingBefore(20);
        paragraph.setSpacingAfter(10);
        document.add(paragraph);

        // 下划线
        PdfPTable line = new PdfPTable(1);
        line.setWidthPercentage(100);
        PdfPCell cell = new PdfPCell();
        cell.setFixedHeight(2);
        cell.setBackgroundColor(PRIMARY_COLOR);
        cell.setBorder(Rectangle.NO_BORDER);
        line.addCell(cell);
        document.add(line);

        addEmptyLines(document, 1);
    }

    private void addChineseParagraph(Document document, String text, Font font) throws DocumentException {
        Paragraph paragraph = new Paragraph(text, font);
        paragraph.setLeading(20);
        paragraph.setFirstLineIndent(24);
        document.add(paragraph);
    }

    private void addEmptyLines(Document document, int count) throws DocumentException {
        for (int i = 0; i < count; i++) {
            document.add(new Paragraph(" ", chineseFontNormal));
        }
    }

    /**
     * 添加能力对比图表
     */
    private void addAbilityComparisonChart(Document document, EvaluationSnapshot.AbilityComparison abilityComparison)
            throws DocumentException, IOException {

        if (abilityComparison.getDimensions() == null || abilityComparison.getDimensions().isEmpty()) {
            return;
        }

        // 创建数据集
        DefaultCategoryDataset dataset = new DefaultCategoryDataset();
        for (EvaluationSnapshot.AbilityComparison.Dimension dim : abilityComparison.getDimensions()) {
            dataset.addValue(dim.getStudentScore(), "当前能力", dim.getLabel());
            dataset.addValue(dim.getTargetRequiredScore(), "目标要求", dim.getLabel());
        }

        // 创建图表
        JFreeChart chart = ChartFactory.createBarChart(
                "能力对比分析",
                "能力维度",
                "分数",
                dataset,
                PlotOrientation.HORIZONTAL,
                true,
                true,
                false
        );

        // 美化图表
        CategoryPlot plot = chart.getCategoryPlot();
        plot.setBackgroundPaint(Color.WHITE);
        plot.setRangeGridlinePaint(BORDER_COLOR);

        // 设置颜色
        BarRenderer renderer = (BarRenderer) plot.getRenderer();
        renderer.setSeriesPaint(0, PRIMARY_COLOR);
        renderer.setSeriesPaint(1, SECONDARY_COLOR);
        renderer.setItemMargin(0.1);
        renderer.setMaximumBarWidth(0.08);

        // 设置中文字体
        java.awt.Font chineseFont = new java.awt.Font("SimSun", java.awt.Font.PLAIN, 10);
        java.awt.Font chineseFontBold = new java.awt.Font("SimSun", java.awt.Font.BOLD, 12);

        CategoryAxis domainAxis = plot.getDomainAxis();
        domainAxis.setTickLabelFont(chineseFont);
        domainAxis.setLabelFont(chineseFontBold);

        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setTickLabelFont(chineseFont);
        rangeAxis.setLabelFont(chineseFontBold);
        rangeAxis.setRange(0, 100);

        chart.getTitle().setFont(chineseFontBold);
        chart.getLegend().setItemFont(chineseFont);

        // 转换为图片
        ByteArrayOutputStream chartStream = new ByteArrayOutputStream();
        ChartUtils.writeChartAsPNG(chartStream, chart, 500, 350);
        byte[] chartBytes = chartStream.toByteArray();

        // 添加图片到PDF
        Image chartImage = Image.getInstance(chartBytes);
        chartImage.scaleToFit(450, 300);
        chartImage.setAlignment(Element.ALIGN_CENTER);
        document.add(chartImage);

        addEmptyLines(document, 1);
    }

    /**
     * 添加阶段计划
     */
    private void addStagePlanSection(Document document, ReportSections.StagePlan stagePlan)
            throws DocumentException {

        if (stagePlan.getMilestones() == null || stagePlan.getMilestones().isEmpty()) {
            return;
        }

        for (int i = 0; i < stagePlan.getMilestones().size(); i++) {
            ReportSections.StagePlan.Milestone milestone = stagePlan.getMilestones().get(i);

            // 阶段标题
            Font stageFont = new Font(chineseBaseFont, 13, Font.BOLD, PRIMARY_COLOR);
            Paragraph stageTitle = new Paragraph(
                    String.format("阶段 %d: %s (%s)", i + 1, milestone.getStageLabel(), milestone.getCycle()),
                    stageFont);
            stageTitle.setSpacingBefore(10);
            stageTitle.setSpacingAfter(6);
            document.add(stageTitle);

            // 目标
            if (milestone.getGoals() != null && !milestone.getGoals().isEmpty()) {
                Font labelFont = new Font(chineseBaseFont, 11, Font.BOLD, TEXT_DARK);
                document.add(new Paragraph("目标:", labelFont));

                Font contentFont = new Font(chineseBaseFont, 11, Font.NORMAL, TEXT_LIGHT);
                for (String goal : milestone.getGoals()) {
                    Paragraph goalPara = new Paragraph("  • " + goal, contentFont);
                    goalPara.setLeading(16);
                    document.add(goalPara);
                }
            }

            // 任务
            if (milestone.getTasks() != null && !milestone.getTasks().isEmpty()) {
                Font labelFont = new Font(chineseBaseFont, 11, Font.BOLD, TEXT_DARK);
                document.add(new Paragraph("任务:", labelFont));

                Font contentFont = new Font(chineseBaseFont, 11, Font.NORMAL, TEXT_LIGHT);
                for (String task : milestone.getTasks()) {
                    Paragraph taskPara = new Paragraph("  • " + task, contentFont);
                    taskPara.setLeading(16);
                    document.add(taskPara);
                }
            }

            // 交付物
            if (milestone.getDeliverables() != null && !milestone.getDeliverables().isEmpty()) {
                Font labelFont = new Font(chineseBaseFont, 11, Font.BOLD, TEXT_DARK);
                document.add(new Paragraph("交付物:", labelFont));

                Font contentFont = new Font(chineseBaseFont, 11, Font.NORMAL, TEXT_LIGHT);
                for (String deliverable : milestone.getDeliverables()) {
                    Paragraph delPara = new Paragraph("  • " + deliverable, contentFont);
                    delPara.setLeading(16);
                    document.add(delPara);
                }
            }

            addEmptyLines(document, 1);
        }
    }

    /**
     * 添加风险与对策
     */
    private void addRiskControlSection(Document document, ReportSections.RiskControl riskControl)
            throws DocumentException {

        if (riskControl.getItems() == null || riskControl.getItems().isEmpty()) {
            return;
        }

        PdfPTable table = new PdfPTable(new float[]{0.8f, 2, 2, 2, 1});
        table.setWidthPercentage(100);
        table.setSpacingBefore(8);

        // 表头
        String[] headers = {"序号", "风险描述", "影响分析", "应对措施", "负责人"};
        Font headerFont = new Font(chineseBaseFont, 10, Font.BOLD, Color.WHITE);
        for (String header : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(header, headerFont));
            cell.setBackgroundColor(PRIMARY_COLOR);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            cell.setPadding(6);
            table.addCell(cell);
        }

        // 数据行
        Font dataFont = new Font(chineseBaseFont, 9, Font.NORMAL, TEXT_DARK);
        List<ReportSections.RiskControl.RiskItem> items = riskControl.getItems();
        for (int i = 0; i < items.size(); i++) {
            ReportSections.RiskControl.RiskItem item = items.get(i);

            // 序号
            PdfPCell numCell = new PdfPCell(new Phrase(String.valueOf(i + 1), dataFont));
            numCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            numCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            table.addCell(numCell);

            // 风险描述
            PdfPCell riskCell = new PdfPCell(new Phrase(item.getRisk(), dataFont));
            riskCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            table.addCell(riskCell);

            // 影响分析
            PdfPCell impactCell = new PdfPCell(new Phrase(item.getImpact(), dataFont));
            impactCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            table.addCell(impactCell);

            // 应对措施
            PdfPCell mitigationCell = new PdfPCell(new Phrase(item.getMitigation(), dataFont));
            mitigationCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            table.addCell(mitigationCell);

            // 负责人
            PdfPCell ownerCell = new PdfPCell(new Phrase(item.getOwner(), dataFont));
            ownerCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            ownerCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            table.addCell(ownerCell);
        }

        document.add(table);
    }

    /**
     * 添加资源建议
     */
    private void addResourceRecommendationsSection(Document document, ReportSections.ResourceRecommendations resources)
            throws DocumentException {

        // 推荐课程
        if (resources.getCourses() != null && !resources.getCourses().isEmpty()) {
            Font sectionFont = new Font(chineseBaseFont, 12, Font.BOLD, TEXT_DARK);
            Paragraph courseTitle = new Paragraph("推荐课程", sectionFont);
            courseTitle.setSpacingBefore(8);
            courseTitle.setSpacingAfter(4);
            document.add(courseTitle);

            Font contentFont = new Font(chineseBaseFont, 11, Font.NORMAL, TEXT_LIGHT);
            for (ReportSections.ResourceRecommendations.Course course : resources.getCourses()) {
                String text = String.format("• %s (%s)", course.getName(), course.getProvider());
                if (course.getUrl() != null) {
                    text += " - " + course.getUrl();
                }
                Paragraph para = new Paragraph(text, contentFont);
                para.setLeading(16);
                document.add(para);
            }
        }

        // 推荐社区
        if (resources.getCommunities() != null && !resources.getCommunities().isEmpty()) {
            Font sectionFont = new Font(chineseBaseFont, 12, Font.BOLD, TEXT_DARK);
            Paragraph commTitle = new Paragraph("推荐社区", sectionFont);
            commTitle.setSpacingBefore(8);
            commTitle.setSpacingAfter(4);
            document.add(commTitle);

            Font contentFont = new Font(chineseBaseFont, 11, Font.NORMAL, TEXT_LIGHT);
            for (ReportSections.ResourceRecommendations.Community community : resources.getCommunities()) {
                String text = "• " + community.getName();
                if (community.getUrl() != null) {
                    text += " - " + community.getUrl();
                }
                Paragraph para = new Paragraph(text, contentFont);
                para.setLeading(16);
                document.add(para);
            }
        }

        // 推荐证书
        if (resources.getCertifications() != null && !resources.getCertifications().isEmpty()) {
            Font sectionFont = new Font(chineseBaseFont, 12, Font.BOLD, TEXT_DARK);
            Paragraph certTitle = new Paragraph("推荐证书", sectionFont);
            certTitle.setSpacingBefore(8);
            certTitle.setSpacingAfter(4);
            document.add(certTitle);

            Font contentFont = new Font(chineseBaseFont, 11, Font.NORMAL, TEXT_LIGHT);
            for (ReportSections.ResourceRecommendations.Certification cert : resources.getCertifications()) {
                String text = String.format("• %s - %s", cert.getName(), cert.getReason());
                Paragraph para = new Paragraph(text, contentFont);
                para.setLeading(16);
                document.add(para);
            }
        }
    }

    /**
     * 添加复盘机制
     */
    private void addReviewMechanismSection(Document document, ReportSections.ReviewMechanism reviewMechanism)
            throws DocumentException {

        // 复盘频率
        if (reviewMechanism.getCadence() != null) {
            Font labelFont = new Font(chineseBaseFont, 11, Font.BOLD, TEXT_DARK);
            document.add(new Paragraph("复盘频率: " + reviewMechanism.getCadence(), labelFont));
        }

        // 检查点
        if (reviewMechanism.getCheckpoints() != null && !reviewMechanism.getCheckpoints().isEmpty()) {
            Font labelFont = new Font(chineseBaseFont, 11, Font.BOLD, TEXT_DARK);
            Paragraph cpTitle = new Paragraph("检查点:", labelFont);
            cpTitle.setSpacingBefore(8);
            document.add(cpTitle);

            Font contentFont = new Font(chineseBaseFont, 11, Font.NORMAL, TEXT_LIGHT);
            for (String checkpoint : reviewMechanism.getCheckpoints()) {
                Paragraph para = new Paragraph("  • " + checkpoint, contentFont);
                para.setLeading(16);
                document.add(para);
            }
        }

        // 调整规则
        if (reviewMechanism.getAdjustmentRule() != null) {
            Font labelFont = new Font(chineseBaseFont, 11, Font.BOLD, TEXT_DARK);
            Paragraph ruleTitle = new Paragraph("调整规则:", labelFont);
            ruleTitle.setSpacingBefore(8);
            document.add(ruleTitle);

            Font contentFont = new Font(chineseBaseFont, 11, Font.NORMAL, TEXT_LIGHT);
            Paragraph rulePara = new Paragraph("  " + reviewMechanism.getAdjustmentRule(), contentFont);
            rulePara.setLeading(16);
            document.add(rulePara);
        }
    }

    /**
     * 页眉页脚事件处理器
     */
    private static class ReportPageEventHelper extends PdfPageEventHelper {
        private final ExportReportRequestDTO.PdfTemplateConfig templateConfig;
        private final BaseFont chineseBaseFont;

        public ReportPageEventHelper(ExportReportRequestDTO.PdfTemplateConfig templateConfig, BaseFont chineseBaseFont) {
            this.templateConfig = templateConfig;
            this.chineseBaseFont = chineseBaseFont;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();

            // 页眉
            if (templateConfig.getHeader() != null && Boolean.TRUE.equals(templateConfig.getHeader().getEnabled())) {
                Font headerFont = new Font(chineseBaseFont, 9, Font.NORMAL, TEXT_LIGHT);
                Phrase header = new Phrase(templateConfig.getHeader().getText(), headerFont);
                ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, header,
                        (document.right() + document.left()) / 2, document.top() + 20, 0);

                // 页眉下划线
                cb.setColorStroke(BORDER_COLOR);
                cb.moveTo(document.left(), document.top() + 10);
                cb.lineTo(document.right(), document.top() + 10);
                cb.stroke();
            }

            // 页脚
            if (templateConfig.getFooter() != null && Boolean.TRUE.equals(templateConfig.getFooter().getEnabled())) {
                Font footerFont = new Font(chineseBaseFont, 9, Font.NORMAL, TEXT_LIGHT);
                Phrase footer = new Phrase(templateConfig.getFooter().getText(), footerFont);
                ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, footer,
                        (document.right() + document.left()) / 2, document.bottom() - 20, 0);
            }

            // 页码
            if (templateConfig.getPagination() != null && Boolean.TRUE.equals(templateConfig.getPagination().getEnabled())) {
                Font pageFont = new Font(chineseBaseFont, 9, Font.NORMAL, TEXT_LIGHT);
                int pageNumber = writer.getPageNumber();
                String pageText = templateConfig.getPagination().getFormat()
                        .replace("{{page}}", String.valueOf(pageNumber))
                        .replace("{{total}}", "?");
                Phrase pagePhrase = new Phrase(pageText, pageFont);
                ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT, pagePhrase,
                        document.right(), document.bottom() - 20, 0);
            }
        }
    }
}
