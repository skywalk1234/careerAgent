package group.profileservice.service;/* I love coding */

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import group.dto.StudentProfile;
import group.profileservice.domain.po.Resume;
import group.profileservice.domain.po.ResumeFull;
import group.profileservice.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class QueryResumeService {
    private final ResumeFullMapper resumeMapper;

    public ResumeFull queryResume(Long userId) {
        QueryWrapper<ResumeFull> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId);
        ResumeFull resumeFull = resumeMapper.selectOne(queryWrapper);

        if (resumeFull != null) {
            return resumeFull;
        }
        return null;
    }



}
