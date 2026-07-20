package group.careerservice.tools;/* I love coding */

import group.careerservice.domain.po.FavoriteJob;
import group.careerservice.domain.dto.JobDocument;
import group.careerservice.domain.response.FavoriteRes;
import group.careerservice.service.JobExploration.SaveJobService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
@Component
@AllArgsConstructor
public class FavoriteTrans {
    private final SaveJobService saveJobService;  // 通过构造函数注入

    public FavoriteRes trans(List<FavoriteJob> favoriteJobs) {
        // 移除static修饰符
        FavoriteRes response = new FavoriteRes();
        List<FavoriteRes.JobInfo> jobInfoList = new ArrayList<>();

        for (FavoriteJob favoriteJob : favoriteJobs) {
            String jobId = favoriteJob.getJobId();
            JobDocument jobDetail = saveJobService.queryJobById(jobId);

            if (jobDetail != null) {
                FavoriteRes.JobInfo jobInfo = new FavoriteRes.JobInfo();
                jobInfo.setJobId(jobDetail.getJobId());
                jobInfo.setJobName(jobDetail.getJobName());
                jobInfo.setCity(jobDetail.getCity());
                jobInfo.setEducationRequirement(jobDetail.getEducationRequirement());
                jobInfo.setSalaryNegotiable(jobDetail.getSalaryNegotiable());
                jobInfo.setSalaryNormalized(jobDetail.getSalaryNormalized());
                jobInfo.setUpdatedAtRaw(jobDetail.getUpdatedAtRaw());
                jobInfo.setFavoritedAt(favoriteJob.getFavoriteAt().toString());
                jobInfoList.add(jobInfo);
            }
        }

        response.setList(jobInfoList);
        response.setTotal(jobInfoList.size());

        return response;
    }
}
