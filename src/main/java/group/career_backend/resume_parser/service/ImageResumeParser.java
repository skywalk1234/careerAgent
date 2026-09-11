package group.career_backend.resume_parser.service;

public interface ImageResumeParser {
    String parse(byte[] imageBytes, String contentType);
}
