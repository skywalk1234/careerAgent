package group.resumeparserservice.domain.enum_;/* I love coding */

public enum TaskStatus {
    PLANNING("planning"),
    AWAITING_APPROVAL("awaiting_approval"),
    RUNNING("running"),
    PAUSED("paused"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELED("canceled");
    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
