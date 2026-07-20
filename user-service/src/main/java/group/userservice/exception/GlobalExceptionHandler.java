package group.userservice.exception;/* I love coding */

import group.common.Result;
import group.exception.RegisterException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // 处理业务异常
    @ExceptionHandler(RegisterException.class)
    public Result<?> handleBusinessException(RegisterException e) {
        log.error("业务异常: {}", e.getMessage(), e);
        return Result.error(e.getCode(), e.getMessage());
    }

    // 处理参数校验异常
//    @ExceptionHandler(MethodArgumentNotValidException.class)
//    public Result<?> handleValidException(MethodArgumentNotValidException e) {
//        String message = e.getBindingResult().getFieldErrors().stream()
//                .map(FieldError::getDefaultMessage)
//                .collect(Collectors.joining(", "));
//        log.error("参数校验异常: {}", message);
//        return Result.error(400, message);
//    }
//
//    // 处理运行时异常
//    @ExceptionHandler(RuntimeException.class)
//    public Result<?> handleRuntimeException(RuntimeException e) {
//        log.error("运行时异常: {}", e.getMessage(), e);
//        return Result.error(500, "系统内部错误");
//    }
//
//    // 处理所有其他异常
//    @ExceptionHandler(Exception.class)
//    public Result<?> handleException(Exception e) {
//        log.error("系统异常: {}", e.getMessage(), e);
//        return Result.error(500, "系统异常");
//    }
//
//    // 处理HTTP相关异常（如404）
//    @ExceptionHandler(NoHandlerFoundException.class)
//    public Result<?> handleNotFoundException(NoHandlerFoundException e) {
//        log.error("资源不存在: {}", e.getRequestURL());
//        return Result.error(404, "请求的资源不存在");
//    }
}
