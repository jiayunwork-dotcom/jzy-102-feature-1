package com.geoviz.terrain.api;

import com.geoviz.terrain.evolution.EvolutionNotFoundException;
import com.geoviz.terrain.validation.InvalidParameterException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理：参数非法 → 400 + 具体原因；演进链/帧不存在 → 404 + 具体原因；
 * 请求体解析失败 → 400。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidParameterException.class)
    public ResponseEntity<Map<String, String>> handleInvalidParameter(InvalidParameterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(EvolutionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleEvolutionNotFound(EvolutionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "请求体格式错误，无法解析为合法参数"));
    }
}
