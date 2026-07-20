package group.profileservice.service;/* I love coding */

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import group.dto.StudentProfile;
import group.profileservice.domain.po.*;
import group.profileservice.mapper.ProfileMapper;
import group.profileservice.mapper.ResumeFullMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

//将StudentProfile这个类拆分，并写入数据库
@Service
@RequiredArgsConstructor
@Slf4j
public class SaveProfileService {

//    private final ProfileMapper profileMapper;
    private final ResumeFullMapper resumeFullMapper;
    public Integer saveProfile(StudentProfile profile, Long userId, String fileName, String fileType) {
        ResumeFull resumeFull = new ResumeFull();
        resumeFull.setUser_id(userId);
        resumeFull.setResumeData(profile);
        resumeFull.setFileName(fileName);
        resumeFull.setFileType(fileType);
        // 设置时间
        resumeFull.setUpdatedAt(LocalDateTime.now());


        // 先查询是否存在
        QueryWrapper<ResumeFull> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);

        if (resumeFullMapper.exists(wrapper)) {
            // 更新
            resumeFullMapper.update(resumeFull, wrapper);
        } else {
            // 插入
            resumeFull.setCreatedAt(LocalDateTime.now());
            resumeFullMapper.insert(resumeFull);
        }

//        profileMapper.insert(getResume(profile, userId));
//        log.info("插入简历成功");
//        profile.getEducation().stream().map(source -> {
//            Education education = new Education();
//            education.setUserId(userId);
//            BeanUtil.copyProperties(source, education, false);
//            return education;
//        }).forEach(profileMapper::insert);
//        log.info("插入教育经历成功");
//        profile.getWorkExperience().stream().map(source -> {
//            WorkExperience exp = new WorkExperience();
//            exp.setUserId(userId);
//            BeanUtil.copyProperties(source, exp, false);
//            return exp;
//        }).forEach(profileMapper::insert);
//        log.info("插入工作经历成功");
//        profile.getCertificates().stream().map(source -> {
//            Certificates certificates = new Certificates();
//            certificates.setUserId(userId);
//            BeanUtil.copyProperties(source, certificates, false);
//            return certificates;
//        }).forEach(profileMapper::insert);
//        log.info("插入证书成功");
        return 1;
    }
    public Resume getResume(StudentProfile profile, Integer userId){
        Resume resume = new Resume();
        resume.setUserId(userId);
        resume.setName(profile.getBasicInfo().getName());
        resume.setPhone(profile.getBasicInfo().getPhone());
        resume.setEmail(profile.getBasicInfo().getEmail());
        resume.setJobIntention(profile.getBasicInfo().getJobIntention());
        resume.setCity(profile.getBasicInfo().getCity());
        resume.setSkills(profile.getSkills());
        resume.setOrganizeExp(profile.getOrganizeExp());
        resume.setProjects(profile.getProjects());
        resume.setSelfEvaluation(profile.getSelfEvaluation());
        resume.setCreatedAt(LocalDateTime.now());
        resume.setUpdatedAt(LocalDateTime.now());
        return resume;
    }

    public Integer deleteProfile(Long userId) {
        QueryWrapper<ResumeFull> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        return resumeFullMapper.delete(wrapper);
    }
}
