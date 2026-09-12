package group.careerservice.service.Route;/* I love coding */


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import group.careerservice.domain.dto.DeepEvalInfoDTO;
import group.careerservice.domain.dto.LatestRouteDTO;
import group.careerservice.domain.dto.PathListDTO;
import group.careerservice.domain.po.AnalysisDraftPO;
import group.careerservice.domain.po.DeepEvalPO;
import group.careerservice.mapper.AnalysisDraftMapper;
import group.careerservice.mapper.DeepEvalMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class RouteListService {
    private final DeepEvalMapper deepEvalMapper;
    private final AnalysisDraftMapper analysisDraftMapper;
    public LatestRouteDTO getLatestRoute(String userId) {
        log.info("开始获取用户{}的最新路线", userId);
        Long userIdLong = Long.parseLong(userId);
        // 使用LambdaQueryWrapper
        LambdaQueryWrapper<DeepEvalPO> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(DeepEvalPO::getUserId, userIdLong)
                .orderByDesc(DeepEvalPO::getUpdatedAt)  // 按更新时间倒序
                .last("LIMIT 1");                       // 取最新一条

        DeepEvalPO deepEvalPO = deepEvalMapper.selectOne(queryWrapper);
        if (deepEvalPO == null) {
            log.info("用户{}没有保存过路线", userId);
            //查询草稿表
            QueryWrapper<AnalysisDraftPO> queryWrapper2 = new QueryWrapper<>();

            queryWrapper2
                    .eq("user_id", userId)
                    .orderByDesc("created_at")  // 按创建时间降序排序
                    .last("LIMIT 1");           // 限制只取一条

            AnalysisDraftPO draft = analysisDraftMapper.selectOne(queryWrapper2);
            if (draft == null) {
                log.info("用户{}没有保存过草稿", userId);
                return null;
            }
            Map<String, String> draftInfo = new HashMap<>();
            draftInfo.put("draftId", draft.getDraftId());
            LatestRouteDTO latestRouteDTO = new LatestRouteDTO(false,
                    null,
                    draftInfo,
                    draft.getUpdatedAt().toString());

            return latestRouteDTO;
        }
        else{
            LatestRouteDTO latestRouteDTO = new LatestRouteDTO(true,
                    new LatestRouteDTO.PathInfo(deepEvalPO.getPathId(), deepEvalPO.getPathName()),
                    null,
                    deepEvalPO.getUpdatedAt().toString());
            return latestRouteDTO;
        }


    }

    public List<PathListDTO> getPathList(String userId) {
        log.info("开始获取用户{}的路线列表", userId);
        Long userIdLong = Long.parseLong(userId);
        // 使用LambdaQueryWrapper
        LambdaQueryWrapper<DeepEvalPO> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(DeepEvalPO::getUserId, userIdLong)
                .orderByDesc(DeepEvalPO::getUpdatedAt);  // 按更新时间倒序

        List<DeepEvalPO> deepEvalPOList = deepEvalMapper.selectList(queryWrapper);

        List<PathListDTO> pathListDTOList = deepEvalPOList.stream()
                .map(po -> {
                    PathListDTO dto = new PathListDTO();
                    dto.setPathId(po.getPathId());
                    dto.setPathName(po.getPathName());
                    dto.setPathNodeCount(po.getPathNodeCount());
                    dto.setFeasibilityScore(po.getFeasibilityScore());
                    dto.setUpdatedAt(po.getUpdatedAt() != null ? po.getUpdatedAt().toString() : null);
                    // 从 deepEvalInfo 中提取 targetJobName
                    DeepEvalInfoDTO deepEvalInfo = po.getDeepEvalInfo();
                    if (deepEvalInfo != null) {
                        dto.setTargetJobName(po.getPathNodes().get(po.getPathNodeCount()-1).getJobName());
                    } else {
                        dto.setTargetJobName(null);
                    }

                    return dto;
                })
                .collect(Collectors.toList());
        return pathListDTOList;

    }

    public Integer deletePath(String userId, String pathId) {
        log.info("开始删除用户{}的路线{}", userId, pathId);
        Long userIdLong = Long.parseLong(userId);
        // 使用LambdaQueryWrapper
        LambdaQueryWrapper<DeepEvalPO> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(DeepEvalPO::getUserId, userIdLong)
                .eq(DeepEvalPO::getPathId, pathId);

        return deepEvalMapper.delete(queryWrapper);
    }

}
