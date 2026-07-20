package group.careerservice.service.JobExploration;/* I love coding */

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import group.careerservice.domain.po.JobNodesPO;
import group.careerservice.domain.po.JobPromotionPO;
import group.careerservice.domain.po.JobTransferPO;
import group.careerservice.domain.response.JobGraphRes;
import group.careerservice.mapper.JobNodesMapper;
import group.careerservice.mapper.JobPromotionMapper;
import group.careerservice.mapper.JobTransferMapper;
import group.dto.JobNodes;
import group.dto.JobPromotion;
import group.dto.JobTransfer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class QueryJobGraphService {

    private final JobNodesMapper jobNodesMapper;
    private final JobTransferMapper jobTransferMapper;
    private final JobPromotionMapper jobPromotionMapper;

    public JobGraphRes queryJobGraph(String categoryName) {
        log.info("开始查询岗位图谱，categoryName: {}", categoryName);

        JobNodesPO centerNode = getNodeByName(categoryName);
        if (centerNode == null) {
            log.warn("未找到对应的岗位节点: {}", categoryName);
            return JobGraphRes.builder()
                    .nodes(new ArrayList<>())
                    .edges(new ArrayList<>())
                    .build();
        }

        String centerNodeId = centerNode.getNodeId();
        Set<String> nodeIdSet = new HashSet<>();
        nodeIdSet.add(centerNodeId);

        List<JobGraphRes.Edge> edges = new ArrayList<>();

        List<JobTransferPO> transferEdges = queryTransferEdges(centerNodeId);
        for (JobTransferPO transfer : transferEdges) {
            nodeIdSet.add(transfer.getSource());
            nodeIdSet.add(transfer.getTarget());

            JobTransfer metadata = transfer.getMetadata();
            String reason = metadata != null && metadata.getReason() != null
                    ? metadata.getReason().getWhy()
                    : "";
            String difficulty = metadata != null && metadata.getReason() != null
                    ? metadata.getReason().getEstimatedDifficulty()
                    : "medium";
            Double similarity = metadata != null ? metadata.getScore() : 0.0;

            edges.add(JobGraphRes.Edge.builder()
                    .source(getNodeNameById(transfer.getSource()))
                    .target(getNodeNameById(transfer.getTarget()))
                    .similarity(similarity)
                    .difficulty(difficulty)
                    .relationType("transition")
                    .reason(reason)
                    .build());
        }

        List<JobPromotionPO> promotionEdges = queryPromotionPath(centerNodeId);
        for (JobPromotionPO promotion : promotionEdges) {
            nodeIdSet.add(promotion.getSource());
            nodeIdSet.add(promotion.getTarget());

            JobPromotion metadata = promotion.getMetadata();
            String reason = "";
            if (metadata != null && metadata.getReason() != null) {
                reason = metadata.getReason().getWhy();
            }
            Double similarity = metadata != null ? metadata.getScore() : 0.0;

            edges.add(JobGraphRes.Edge.builder()
                    .source(getNodeNameById(promotion.getSource()))
                    .target(getNodeNameById(promotion.getTarget()))
                    .similarity(similarity)
                    .difficulty("high")
                    .relationType("promotion")
                    .reason(reason)
                    .build());
        }

        List<JobGraphRes.Node> nodes = new ArrayList<>();
        for (String nodeId : nodeIdSet) {
            JobNodesPO nodePO = getNodeById(nodeId);
            if (nodePO != null) {
                JobNodes metadata = nodePO.getMetadata();
                List<String> industryTags = metadata != null ? metadata.getIndustryTags() : new ArrayList<>();
                String summary = metadata != null ? metadata.getSummary() : "";

                nodes.add(JobGraphRes.Node.builder()
                        .jobName(nodePO.getNodeName())
                        .industryTags(industryTags)
                        .jobDescription(summary)
                        .build());
            }
        }

        log.info("岗位图谱查询完成，节点数: {}, 边数: {}", nodes.size(), edges.size());
        return JobGraphRes.builder()
                .nodes(nodes)
                .edges(edges)
                .build();
    }

    private JobNodesPO getNodeByName(String nodeName) {
        QueryWrapper<JobNodesPO> wrapper = new QueryWrapper<>();
        wrapper.eq("node_name", nodeName);
        return jobNodesMapper.selectOne(wrapper);
    }

    private JobNodesPO getNodeById(String nodeId) {
        QueryWrapper<JobNodesPO> wrapper = new QueryWrapper<>();
        wrapper.eq("node_id", nodeId);
        return jobNodesMapper.selectOne(wrapper);
    }

    private String getNodeNameById(String nodeId) {
        JobNodesPO node = getNodeById(nodeId);
        return node != null ? node.getNodeName() : nodeId;
    }

    private List<JobTransferPO> queryTransferEdges(String nodeId) {
        QueryWrapper<JobTransferPO> wrapper = new QueryWrapper<>();
        wrapper.eq("source", nodeId);
        return jobTransferMapper.selectList(wrapper);
    }

    private List<JobPromotionPO> queryPromotionPath(String centerNodeId) {
        List<JobPromotionPO> allPromotions = new ArrayList<>();
        Set<String> visitedForward = new HashSet<>();
        Set<String> visitedBackward = new HashSet<>();

        Queue<String> forwardQueue = new LinkedList<>();
        forwardQueue.offer(centerNodeId);
        visitedForward.add(centerNodeId);

        while (!forwardQueue.isEmpty()) {
            String currentId = forwardQueue.poll();

            QueryWrapper<JobPromotionPO> forwardWrapper = new QueryWrapper<>();
            forwardWrapper.eq("source", currentId);
            List<JobPromotionPO> forwardEdges = jobPromotionMapper.selectList(forwardWrapper);

            for (JobPromotionPO edge : forwardEdges) {
                allPromotions.add(edge);
                String targetId = edge.getTarget();
                if (!visitedForward.contains(targetId)) {
                    visitedForward.add(targetId);
                    forwardQueue.offer(targetId);
                }
            }
        }

        Queue<String> backwardQueue = new LinkedList<>();
        backwardQueue.offer(centerNodeId);
        visitedBackward.add(centerNodeId);

        while (!backwardQueue.isEmpty()) {
            String currentId = backwardQueue.poll();

            QueryWrapper<JobPromotionPO> backwardWrapper = new QueryWrapper<>();
            backwardWrapper.eq("target", currentId);
            List<JobPromotionPO> backwardEdges = jobPromotionMapper.selectList(backwardWrapper);

            for (JobPromotionPO edge : backwardEdges) {
                allPromotions.add(edge);
                String sourceId = edge.getSource();
                if (!visitedBackward.contains(sourceId)) {
                    visitedBackward.add(sourceId);
                    backwardQueue.offer(sourceId);
                }
            }
        }

        return allPromotions;
    }
}
