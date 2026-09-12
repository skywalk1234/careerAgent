package group.careerservice.service.JobExploration;/* I love coding */

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import group.careerservice.domain.po.JobRelationPO;
import group.careerservice.domain.po.VerticalPathPO;
import group.careerservice.domain.response.JobRelationRes;
import group.careerservice.mapper.JobPromotionMapper;
import group.careerservice.mapper.JobTransferMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class QueryJobRelationService {
    private final JobPromotionMapper jobPromotionMapper;
    private final JobTransferMapper jobTransferMapper;
}
