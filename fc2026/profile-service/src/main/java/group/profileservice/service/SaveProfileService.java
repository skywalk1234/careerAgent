package group.profileservice.service;/* I love coding */

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import group.dto.StudentProfile;
import group.profileservice.domain.po.ResumeFull;
import group.profileservice.mapper.ResumeFullMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

//将StudentProfile这个类拆分，并写入数据库
@Service
@RequiredArgsConstructor
@Slf4j
public class SaveProfileService {

    private final ResumeFullMapper resumeFullMapper;

    /**
     * 保存/更新一份简历（一个学生可有多份）。
     * profileId 非空：按 user_id + profile_id 定位更新，不存在则按新简历插入；
     * profileId 为空（上传解析等走 MQ 的路径）：落库时自动生成 UUID 作为简历id 并插入。
     */
    public Integer saveProfile(StudentProfile profile, Long userId, String profileId, String fileName, String fileType) {
        ResumeFull resumeFull = new ResumeFull();
        resumeFull.setUser_id(userId);
        resumeFull.setFileName(fileName);
        resumeFull.setFileType(fileType);
        resumeFull.setUpdatedAt(LocalDateTime.now());

        if (StringUtils.hasText(profileId)) {
            // 编辑已有简历：user_id + profile_id 定位
            profile.setProfileId(profileId);
            resumeFull.setProfileId(profileId);
            resumeFull.setResumeData(profile);

            QueryWrapper<ResumeFull> wrapper = new QueryWrapper<>();
            wrapper.eq("user_id", userId).eq("profile_id", profileId);
            if (resumeFullMapper.exists(wrapper)) {
                // 更新（null 字段默认不写入，created_at 保留原值）
                resumeFullMapper.update(resumeFull, wrapper);
            } else {
                // 前端生成的 id 在库中不存在，按新简历插入
                resumeFull.setCreatedAt(LocalDateTime.now());
                resumeFullMapper.insert(resumeFull);
            }
        } else {
            // 新简历：落库时生成 profile_id
            String newProfileId = UUID.randomUUID().toString();
            profile.setProfileId(newProfileId);
            resumeFull.setProfileId(newProfileId);
            resumeFull.setResumeData(profile);
            resumeFull.setCreatedAt(LocalDateTime.now());
            resumeFullMapper.insert(resumeFull);
        }
        return 1;
    }

    /** 查询用户全部简历，按最近更新排序（最新的在前），供前端标签页展示 */
    public List<ResumeFull> listResumes(Long userId) {
        QueryWrapper<ResumeFull> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId).orderByDesc("updated_at");
        return resumeFullMapper.selectList(wrapper);
    }

    public Integer deleteProfile(Long userId) {
        QueryWrapper<ResumeFull> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        return resumeFullMapper.delete(wrapper);
    }
}
