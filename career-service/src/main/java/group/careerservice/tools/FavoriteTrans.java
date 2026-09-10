package group.careerservice.tools;/* I love coding */

import group.careerservice.domain.po.FavoriteJob;
import group.careerservice.domain.dto.JobVectorItem;
import group.careerservice.domain.response.FavoriteRes;
import group.careerservice.service.JobExploration.JobVectorQueryService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 收藏记录（MySQL favorite_jobs）→ 岗位详情（pgvector job_detail_vector）的拼装。
 *
 * <p>改造前这里查的是 ES，现在走 {@link JobVectorQueryService}（内部 PG 查不到会回退 ES），
 * 和岗位探索页用同一套数据源，避免两边 jobId 对不上导致收藏列表空白。
 */
@Component
@AllArgsConstructor
public class FavoriteTrans {
    private final JobVectorQueryService jobVectorQueryService;

    public FavoriteRes trans(List<FavoriteJob> favoriteJobs) {
        FavoriteRes response = new FavoriteRes();
        List<FavoriteRes.JobInfo> jobInfoList = new ArrayList<>();

        for (FavoriteJob favoriteJob : favoriteJobs) {
            String jobId = favoriteJob.getJobId();
            JobVectorItem jobDetail = jobVectorQueryService.queryJobById(jobId);

            if (jobDetail != null) {
                FavoriteRes.JobInfo jobInfo = new FavoriteRes.JobInfo();
                jobInfo.setJobId(jobDetail.getJobId());
                jobInfo.setJobName(jobDetail.getJobName());
                jobInfo.setCompanyName(jobDetail.getCompanyName());
                jobInfo.setCity(jobDetail.getCity());
                jobInfo.setEdu(jobDetail.getEdu());
                jobInfo.setSalaryText(jobDetail.getSalaryText());
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
