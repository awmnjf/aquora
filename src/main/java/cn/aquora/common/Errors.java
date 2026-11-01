package cn.aquora.common;

import java.util.Map;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> api(ApiException e) {
    return ResponseEntity.status(e.status).body(Map.of("message", e.getMessage()));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<?> bad(Exception e) {
    return ResponseEntity.badRequest().body(Map.of("message", "输入不完整或格式不正确，请检查后重试"));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> conflict(Exception e) {
    return ResponseEntity.status(409).body(Map.of("message", "数据已存在或已发生变化，请刷新后重试"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unknown(Exception e) {
    LoggerFactory.getLogger(Errors.class).error("Request failed", e);
    return ResponseEntity.internalServerError().body(Map.of("message", "服务暂时无法完成操作，请稍后重试"));
  }
}
