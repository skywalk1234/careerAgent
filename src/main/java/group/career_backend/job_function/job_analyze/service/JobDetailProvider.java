package group.career_backend.job_function.job_analyze.service;

/** Adapter boundary for the job_explore module. */
public interface JobDetailProvider {
    Object getJobDetail(String jobId);
}
