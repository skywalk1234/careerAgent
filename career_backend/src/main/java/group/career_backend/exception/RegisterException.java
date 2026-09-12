package group.career_backend.exception;/* I love coding */

public class RegisterException extends CommonException {

    public RegisterException(String message) {
        super(message, 500);
    }

    public RegisterException(String message, Throwable cause) {
        super(message, cause, 500);
    }
}
