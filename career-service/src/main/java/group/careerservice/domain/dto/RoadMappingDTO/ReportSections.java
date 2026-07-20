package group.careerservice.domain.dto.RoadMappingDTO;/* I love coding */

import lombok.Data;
import java.io.Serializable;
import java.util.List;

@Data
public class ReportSections implements Serializable {
    private ExecutiveSummary executiveSummary;
    private CurrentAssessment currentAssessment;
    private TargetAnalysis targetAnalysis;
    private PathStrategy pathStrategy;
    private StagePlan stagePlan;
    private RiskControl riskControl;
    private ResourceRecommendations resourceRecommendations;
    private ReviewMechanism reviewMechanism;

    @Data
    public static class ExecutiveSummary implements Serializable {
        private String title;
        private String content;
    }

    @Data
    public static class CurrentAssessment implements Serializable {
        private String title;
        private String content;
    }

    @Data
    public static class TargetAnalysis implements Serializable {
        private String title;
        private String content;
    }

    @Data
    public static class PathStrategy implements Serializable {
        private String title;
        private String content;
    }

    @Data
    public static class StagePlan implements Serializable {
        private String title;
        private List<Milestone> milestones;

        @Data
        public static class Milestone implements Serializable {
            private String stageLabel;
            private String cycle;
            private List<String> goals;
            private List<String> tasks;
            private List<String> deliverables;
        }
    }

    @Data
    public static class RiskControl implements Serializable {
        private String title;
        private List<RiskItem> items;

        @Data
        public static class RiskItem implements Serializable {
            private String risk;
            private String impact;
            private String mitigation;
            private String owner;
        }
    }

    @Data
    public static class ResourceRecommendations implements Serializable {
        private String title;
        private List<Course> courses;
        private List<Community> communities;
        private List<Certification> certifications;

        @Data
        public static class Course implements Serializable {
            private String name;
            private String provider;
            private String url;
        }

        @Data
        public static class Community implements Serializable {
            private String name;
            private String url;
        }

        @Data
        public static class Certification implements Serializable {
            private String name;
            private String reason;
        }
    }

    @Data
    public static class ReviewMechanism implements Serializable {
        private String title;
        private String cadence;
        private List<String> checkpoints;
        private String adjustmentRule;
    }
}
