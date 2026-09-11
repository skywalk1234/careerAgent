package group.career_backend.resume_parser.service;

import group.career_backend.resume_parser.domain.dto.ResumeParseMessage;

public interface ResumeParseTaskService {
    void parse(ResumeParseMessage message);
}
