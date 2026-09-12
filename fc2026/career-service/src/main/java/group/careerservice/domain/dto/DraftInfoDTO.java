package group.careerservice.domain.dto;/* I love coding */

import group.careerservice.domain.vo.SimpleRouteVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DraftInfoDTO {
    /**
     * 草稿ID
     */
    private String draftId;

    /**
     * 路径节点列表
     */
    private List<SimpleRouteVO.PathNode> pathNodes;

    /**
     * 路径边列表
     */
    private List<CareerPathEvaluation.PathEdge> pathEdges;

    /**
     * 评估结果
     */
    private CareerPathEvaluation.Evaluation evaluation;

    private String createdAt;
    private String updatedAt;


}
