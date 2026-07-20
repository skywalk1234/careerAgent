package group.careerservice.domain.response;/* I love coding */

import group.careerservice.domain.dto.DeepEvalInfoDTO;
import group.careerservice.domain.dto.JobNodeDTO;
import group.careerservice.domain.vo.SimpleRouteVO;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 路径信息响应DTO
 */
@Data
public class DeepEvalStatusRes implements Serializable {
    private String pathId;
    private String pathName;
    private List<SimpleRouteVO.PathNode> pathNodes;
    private List<DeepEvalInfoDTO.PathEdge> pathEdges;         // 使用已存在的 PathEdge
    private DeepEvalInfoDTO.Evaluation evaluation;           // 使用已存在的 Evaluation
    private String createdAt;
    private String updatedAt;
}
