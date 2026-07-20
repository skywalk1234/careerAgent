package group.careerservice.domain.response;/* I love coding */

import group.careerservice.domain.dto.CareerPathEvaluation;
import group.careerservice.domain.vo.SimpleRouteVO;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

/**
 * 实时评估响应类
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RealTimeEvalRes {
    /**
     * 草稿ID
     */
    private String draftId;

    /**
     * 生成模式
     */
    private String mode;

    /**
     * 生成时间
     */
    private String generatedAt;

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


}
