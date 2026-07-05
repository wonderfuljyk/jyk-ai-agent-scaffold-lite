package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * 统一处理 Controller 层的各类异常，避免在每个方法中重复 try-catch
 * @author jyk
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理参数校验失败异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Response<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", message);
        return Response.<Void>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info(message)
                .build();
    }

    /**
     * 处理业务异常
     */
    @ExceptionHandler(AppException.class)
    public Response<Void> handleAppException(AppException e) {
        log.warn("业务异常 code:{} info:{}", e.getCode(), e.getInfo());
        return Response.<Void>builder()
                .code(e.getCode())
                .info(e.getInfo())
                .build();
    }

    /**
     * 处理未知异常
     */
    @ExceptionHandler(Exception.class)
    public Response<Void> handleGeneralException(Exception e) {
        log.error("未知异常", e);
        String detail = e.getClass().getSimpleName() + ": " +
                (e.getMessage() != null ? e.getMessage() : "(无消息)");
        return Response.<Void>builder()
                .code(ResponseCode.UN_ERROR.getCode())
                .info(detail)
                .build();
    }
}
