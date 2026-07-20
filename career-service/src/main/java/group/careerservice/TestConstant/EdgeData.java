package group.careerservice.TestConstant;/* I love coding */


import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class EdgeData {
    @JsonProperty("meta")
    private Meta meta;

    @JsonProperty("edges")
    private List<Edge> edges;

    @Data
    public static class Meta {
        @JsonProperty("step")
        private int step;

        @JsonProperty("existingEdges")
        private int existingEdges;

        @JsonProperty("predictedEdges")
        private int predictedEdges;

        @JsonProperty("totalEdges")
        private int totalEdges;

        @JsonProperty("threshold")
        private double threshold;

        @JsonProperty("topKPerSource")
        private int topKPerSource;

        @JsonProperty("timestamp")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime timestamp;
    }

    @Data
    public static class Edge {
        @JsonProperty("source")
        private String source;

        @JsonProperty("target")
        private String target;

        @JsonProperty("relationType")
        private String relationType;

        @JsonProperty("similarity")
        private double similarity;

        @JsonProperty("skillOverlap")
        private double skillOverlap;

        @JsonProperty("gapCount")
        private int gapCount;

        @JsonProperty("levelJump")
        private int levelJump;

        @JsonProperty("difficulty")
        private String difficulty;

        @JsonProperty("reason")
        private String reason;

        @JsonProperty("isPredicted")
        private boolean isPredicted;

        @JsonProperty("predProb")
        private double predProb;
    }
}
