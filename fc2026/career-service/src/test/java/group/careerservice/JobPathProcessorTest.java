package group.careerservice;/* I love coding */

// JobPathProcessorTest.java
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.careerservice.domain.po.JobNodesPO;
import group.careerservice.domain.po.JobPromotionPO;
import group.careerservice.domain.po.JobTransferPO;
import group.careerservice.domain.po.VerticalPathPO;
import group.careerservice.mapper.JobNodesMapper;
import group.careerservice.mapper.JobPromotionMapper;
import group.careerservice.mapper.JobTransferMapper;
import group.dto.JobNodes;
import group.dto.JobPromotion;
import group.dto.JobTransfer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
@SpringBootTest
@Slf4j
@ActiveProfiles("test")
public class JobPathProcessorTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JobTransferMapper jobTransferMapper;
    @Autowired
    private JobPromotionMapper jobPromotionMapper;
    @Autowired
    private JobNodesMapper jobNodesMapper;

    @Test
    public void writeTransferJsonData() throws IOException {
        // 1. 读取 JSON 文件内容
        // 假设文件位于 src/test/resources/job_transfer.json 或 src/main/resources/job_transfer.json
        ClassPathResource resource = new ClassPathResource("job_transfer.json");
        if (!resource.exists()) {
            log.error("文件 job_transfer.json 未找到，请检查路径是否正确 (通常在 resources 目录下)");
            return;
        }

        String jsonContent;
        try (InputStream inputStream = resource.getInputStream()) {
            jsonContent = StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
        }

        log.info("成功读取 JSON 文件，长度: {} 字符", jsonContent.length());

        // 2. 反序列化为 List<JobTransfer>
        // 使用 TypeReference 来处理泛型列表的反序列化
        List<JobTransfer> transferList = objectMapper.readValue(
                jsonContent,
                new TypeReference<List<JobTransfer>>() {}
        );

        if (transferList == null || transferList.isEmpty()) {
            log.warn("JSON 文件中没有解析出任何数据");
            return;
        }

        log.info("成功解析出 {} 条转移记录", transferList.size());

        // 3. 转换为 PO 对象 (实体类)
        List<JobTransferPO> poList = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (JobTransfer dto : transferList) {
            JobTransferPO po = new JobTransferPO();
            po.setSource(dto.getSource());
            po.setTarget(dto.getTarget());
            po.setMetadata(dto);
            po.setCreatedAt(now);
            po.setUpdatedAt(now);

            jobTransferMapper.insert(po);
            log.info("✅ 成功向 database 写入条转移关系数据");
        }
    }

    @Test
    public void writePromotionJsonData() throws IOException {
        // 1. 读取 JSON 文件内容
        // 确保 job_promotion.json 位于 src/test/resources 或 src/main/resources 目录下
        ClassPathResource resource = new ClassPathResource("job_promotion.json");
        if (!resource.exists()) {
            log.error("文件 job_promotion.json 未找到，请检查路径是否正确");
            return;
        }

        String jsonContent;
        try (InputStream inputStream = resource.getInputStream()) {
            jsonContent = StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
        }

        log.info("成功读取 Promotion JSON 文件，长度: {} 字符", jsonContent.length());

        // 2. 反序列化为 List<JobPromotion>
        // 使用 TypeReference 处理泛型列表
        List<JobPromotion> promotionList = objectMapper.readValue(
                jsonContent,
                new TypeReference<List<JobPromotion>>() {}
        );

        if (promotionList == null || promotionList.isEmpty()) {
            log.warn("JSON 文件中没有解析出任何晋升记录");
            return;
        }

        log.info("成功解析出 {} 条晋升记录", promotionList.size());

        // 3. 转换为 PO 对象 (实体类)
        List<JobPromotionPO> poList = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (JobPromotion dto : promotionList) {
            JobPromotionPO po = new JobPromotionPO();
            po.setSource(dto.getSource());
            po.setTarget(dto.getTarget());
            po.setMetadata(dto);


            po.setCreatedAt(now);
            po.setUpdatedAt(now);

            poList.add(po);
        }

        // 4. 批量写入数据库
        // 注意：这里假设您注入的是 Service (包含 saveBatch)
        // 如果您只注入了 Mapper 且 Mapper 没有扩展 saveBatch 方法，请改用循环 insert 或注入 IService
        if (!poList.isEmpty()) {

            int successCount = 0;
            for (JobPromotionPO po : poList) {
                if (jobPromotionMapper.insert(po) > 0) {
                    successCount++;
                }
            }

            log.info("✅ 成功向数据库写入 {} 条晋升关系数据", successCount);

        }
    }

    @Test
    public void writeNodesJsonData() throws IOException {
        // 1. 读取 JSON 文件内容
        // 确保 job_nodes.json 位于 src/test/resources 或 src/main/resources 目录下
        ClassPathResource resource = new ClassPathResource("job_nodes.json");
        if (!resource.exists()) {
            log.error("文件 job_nodes.json 未找到，请检查路径是否正确");
            return;
        }

        String jsonContent;
        try (InputStream inputStream = resource.getInputStream()) {
            jsonContent = StreamUtils.copyToString(inputStream, StandardCharsets.UTF_8);
        }

        log.info("成功读取 Nodes JSON 文件，长度: {} 字符", jsonContent.length());

        // 2. 反序列化为 List<JobNodes>
        List<JobNodes> nodeList = objectMapper.readValue(
                jsonContent,
                new TypeReference<List<JobNodes>>() {}
        );

        if (nodeList == null || nodeList.isEmpty()) {
            log.warn("JSON 文件中没有解析出任何节点记录");
            return;
        }

        log.info("成功解析出 {} 条节点记录", nodeList.size());

        // 3. 转换为 PO 对象 (实体类)
        List<JobNodesPO> poList = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (JobNodes dto : nodeList) {
            JobNodesPO po = new JobNodesPO();

            // --- 情况 A: 如果数据库表字段与 DTO 基本对应 (扁平结构) ---
            // 请根据实际字段名进行设置，例如：
            po.setNodeId(dto.getNodeId());       // 假设 DTO 中有 getNodeId()
            po.setNodeName(dto.getNodeName());
            po.setCanonicalJobId(dto.getCanonicalJobId());
            po.setMetadata(dto);

            po.setCreatedAt(now);
            po.setUpdatedAt(now);

            poList.add(po);
        }

        // 4. 批量写入数据库
        if (!poList.isEmpty()) {
            int successCount = 0;
            // 使用循环插入以确保兼容性 (如果未注入 Service)
            for (JobNodesPO po : poList) {
                // 如果主键由数据库自增，确保 PO 中主键为 null
                // 如果主键在 JSON 中已生成，确保 PO 中已设置
                if (jobNodesMapper.insert(po) > 0) {
                    successCount++;
                }
            }

            log.info("✅ 成功向数据库写入 {} 条节点数据", successCount);

            // 如果您注入了 Service (IJobNodesService)，可以使用更高效的批量插入：
            // jobNodesService.saveBatch(poList);
        }
    }

}
