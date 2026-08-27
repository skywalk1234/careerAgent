package group.resumeparserservice.controller;/* I love coding */

import group.resumeparserservice.domain.dto.ResumeParseMessage;
import group.resumeparserservice.service.AI_parser;
import group.resumeparserservice.service.AI_score;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class FileListener {
    @Autowired
    private AI_parser ai_parser;
    @Autowired
    private AI_score ai_score;

    private final RabbitTemplate rabbitTemplate;
    @RabbitListener(queues = "file_tran")
    public void listen_file_exchange(ResumeParseMessage message) throws  Exception{
        System.out.println("消费者接收到ResumeParseMessage对象：");
        System.out.println("jobId: " + message.getUserId());
        System.out.println("fileName: " + message.getFileName());
        System.out.println("fileType: " + message.getFileType());
        System.out.println("parseMode: " + message.getParseMode());
        // ... 处理业务逻辑
        byte[] fileContent = message.getFileContent();
        // 图片模式：fileContent 中是 AI 视觉模型解析出的 JSON 文本，直接使用
        // PDF 模式：需要用 PDFBox 抽取文本
        String textContent;
        if ("image".equalsIgnoreCase(message.getParseMode())) {
            textContent = new String(fileContent, StandardCharsets.UTF_8);
        } else {
            textContent = parsePdfFromBytes(fileContent);
        }
        //提取文字成功了
//        System.out.println("解析内容："+ textContent);
        String markdown = null;

        try{
            markdown = ai_parser.parse(textContent);
        }catch (Exception e){
            log.error("AI整理简历Markdown失败", e);
        }
        //发消息
        log.info("Markdown整理完成");
        String profile_que = "profile_storage";
        if (markdown != null) {
            Map<String, Object> profile_msg = new HashMap<>();
            profile_msg.put("userId", message.getUserId());
            profile_msg.put("profileData", markdown);
            profile_msg.put("fileName", message.getFileName());
            profile_msg.put("fileType", message.getFileType());
            profile_msg.put("timestamp", System.currentTimeMillis());

            rabbitTemplate.convertAndSend(profile_que, profile_msg);
            log.info("成功发送给简历存储服务");



        //调用模型评分
//            共用同一个profile_msg对象，前面已经填了userId了
            String evaluation_json = null;
            try{
                evaluation_json = ai_score.resume_score(markdown);
                log.info("评分完成");
            }catch (Exception e){
                log.error("AI评分失败", e);
            }
            String score_que = "eval_storage";
            if (evaluation_json != null) {
                profile_msg.put("profileData", evaluation_json);
            }


            rabbitTemplate.convertAndSend(score_que, profile_msg);
            log.info("成功发送给评分存储服务");
        }
    }

    /***
     * 解析PDF文件为文本
     *
     * @param fileContent PDF文件的字节数组
     * @return 解析后的文本内容
     * @throws Exception 如果解析过程中发生异常
     */
    public String parsePdfFromBytes(byte[] fileContent) throws Exception {
        log.info("开始解析PDF文件，大小: {} bytes", fileContent.length);

        try (InputStream inputStream = new ByteArrayInputStream(fileContent);
             PDDocument document = PDDocument.load(inputStream)) {

            // 使用PDFTextStripper提取文本
            PDFTextStripper textStripper = new PDFTextStripper();
            textStripper.setSortByPosition(true); // 按位置排序，保持文本顺序
            textStripper.setWordSeparator(" ");   // 设置单词分隔符

            // 获取所有页面文本
            String textContent = textStripper.getText(document);

            log.info("PDF解析完成，文本长度: {}, 页数: {}",
                    textContent.length(), document.getNumberOfPages());

            // 构建解析结果


            return textContent;

        } catch (IOException e) {
            log.error("PDF解析失败", e);
            throw new RuntimeException("PDF文件解析失败: " + e.getMessage(), e);
        }
    }
}
