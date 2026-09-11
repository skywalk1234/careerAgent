package group.career_backend.job_function.interview_report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import group.career_backend.job_function.interview_report.domain.po.InterviewReport;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InterviewReportMapper extends BaseMapper<InterviewReport> {
}
