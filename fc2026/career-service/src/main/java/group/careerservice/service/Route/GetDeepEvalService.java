package group.careerservice.service.Route;/* I love coding */

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import group.careerservice.domain.po.DeepEvalPO;
import group.careerservice.domain.response.DeepEvalStatusRes;
import group.careerservice.mapper.DeepEvalMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class GetDeepEvalService {
    private final DeepEvalMapper deepEvalMapper;
    public DeepEvalStatusRes getEvalStatus(String saveJobId){
        DeepEvalPO deepEvalPO = getEvalPo(saveJobId);

        if (deepEvalPO == null || deepEvalPO.getDeepEvalInfo() == null){
            return null;
        }
        DeepEvalStatusRes deepEvalStatusRes = new DeepEvalStatusRes();
        deepEvalStatusRes.setEvaluation(deepEvalPO.getDeepEvalInfo().getEvaluation());
        deepEvalStatusRes.setPathEdges(deepEvalPO.getDeepEvalInfo().getPathEdges());
        deepEvalStatusRes.setPathId(deepEvalPO.getPathId());
        deepEvalStatusRes.setPathName(deepEvalPO.getPathName());
        deepEvalStatusRes.setPathNodes(deepEvalPO.getPathNodes());
        deepEvalStatusRes.setCreatedAt(deepEvalPO.getUpdatedAt().toString());
        deepEvalStatusRes.setUpdatedAt(deepEvalPO.getUpdatedAt().toString());

        return deepEvalStatusRes;
    }
    public DeepEvalPO getEvalPo(String saveJobId){

        QueryWrapper<DeepEvalPO> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("save_job_id", saveJobId);
        if (deepEvalMapper.selectCount(queryWrapper) != 0) {
            log.info("查询到深度评估结果");
        }
        return deepEvalMapper.selectOne(queryWrapper);
    }

    public DeepEvalStatusRes getEvalByPathId(String pathId) {
        DeepEvalPO deepEvalPO = getEvalPoByPathId(pathId);
        if (deepEvalPO == null || deepEvalPO.getDeepEvalInfo() == null) {
            throw new RuntimeException("未找到对应的深度评估信息");
        }

        DeepEvalStatusRes deepEvalStatusRes = new DeepEvalStatusRes();
        deepEvalStatusRes.setEvaluation(deepEvalPO.getDeepEvalInfo().getEvaluation());
        deepEvalStatusRes.setPathEdges(deepEvalPO.getDeepEvalInfo().getPathEdges());
        deepEvalStatusRes.setPathId(deepEvalPO.getPathId());
        deepEvalStatusRes.setPathName(deepEvalPO.getPathName());
        deepEvalStatusRes.setPathNodes(deepEvalPO.getPathNodes());
        deepEvalStatusRes.setCreatedAt(deepEvalPO.getUpdatedAt().toString());
        deepEvalStatusRes.setUpdatedAt(deepEvalPO.getUpdatedAt().toString());

        return deepEvalStatusRes;
    }

    public DeepEvalPO getEvalPoByPathId(String pathId) {
        QueryWrapper<DeepEvalPO> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("path_id", pathId);

        DeepEvalPO result = deepEvalMapper.selectOne(queryWrapper);
        if (result != null) {
            log.info("根据pathId查询到深度评估结果, pathId: {}", pathId);
        } else {
            log.warn("未找到对应的深度评估记录, pathId: {}", pathId);
        }

        return result;
    }

}
