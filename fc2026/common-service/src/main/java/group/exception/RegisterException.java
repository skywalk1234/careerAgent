package group.exception;/* I love coding */

public class RegisterException extends CommonException{
    // 带有消息的构造函数
    public RegisterException(String message) {
        super(message,500);
    }

    // 带有消息和原因的构造函数
    public RegisterException(String message, Throwable cause) {
        super(message, cause,500);
    }
}
