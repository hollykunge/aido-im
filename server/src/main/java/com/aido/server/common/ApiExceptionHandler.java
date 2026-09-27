package com.aido.server.common;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** 错误统一返回 RFC 9457 Problem Details（application/problem+json）。 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  @ExceptionHandler(NotFoundException.class)
  ProblemDetail notFound(NotFoundException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail badRequest(IllegalArgumentException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(IllegalStateException.class)
  ProblemDetail conflict(IllegalStateException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
  }

  /** 上传的文件超过大小上限。 */
  @Override
  protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
      MaxUploadSizeExceededException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
        .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, "文件不能超过 50 MB"));
  }

  /** 校验失败时把第一个字段错误放进 detail，方便前端直接展示。 */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    var field = e.getBindingResult().getFieldError();
    var detail = field == null ? "参数不合法" : field.getField() + " " + field.getDefaultMessage();
    return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail));
  }
}
