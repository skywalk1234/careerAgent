package group.profileservice.tools;/* I love coding */

import group.dto.StudentProfile;

import java.util.ArrayList;
import java.util.List;

public class StudentProfileChecker {

    /**
     * 检查 StudentProfile 中为空的字段
     */
    public static List<String> checkNullFields(StudentProfile profile) {
        List<String> nullFields = new ArrayList<>();

        if (profile == null) {
            nullFields.add("StudentProfile对象本身为null");
            return nullFields;
        }

        // 检查各个字段
        if (profile.getBasicInfo() == null) {
            nullFields.add("basicInfo");
        }

        if (profile.getEducation() == null || profile.getEducation().isEmpty()) {
            nullFields.add("education");
        }

        if (profile.getWorkExperience() == null || profile.getWorkExperience().isEmpty()) {
            nullFields.add("workExperience");
        }

        if (profile.getSkills() == null || profile.getSkills().isEmpty()) {
            nullFields.add("skills");
        }

        if (profile.getCertificates() == null || profile.getCertificates().isEmpty()) {
            nullFields.add("certificates");
        }

        if (profile.getOrganizeExp() == null || profile.getOrganizeExp().isEmpty()) {
            nullFields.add("organizeExp");
        }

        if (profile.getProjects() == null || profile.getProjects().isEmpty()) {
            nullFields.add("projects");
        }

        if (profile.getSelfEvaluation() == null || profile.getSelfEvaluation().trim().isEmpty()) {
            nullFields.add("selfEvaluation");
        }

        return nullFields;
    }
}
