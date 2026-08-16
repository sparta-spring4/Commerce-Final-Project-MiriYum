package com.miriyum.global.exception;

import com.miriyum.domain.store.error.StoreErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * MVC 계층에서 발생한 예외를 일관된 API 오류 응답으로 변환한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String INVALID_VALUE_REASON = "유효하지 않은 값입니다.";
    private static final String REQUIRED_VALUE_REASON = "필수 입력값입니다.";
    private static final Comparator<ValidationErrorDetail> DETAIL_ORDER =
            Comparator.comparing(ValidationErrorDetail::field)
                    .thenComparing(ValidationErrorDetail::reason);

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<ErrorResponse> handleServiceException(ServiceException exception) {
        return response(exception.getErrorCode());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception
    ) {
        return validationResponse(bindingDetails(exception.getBindingResult()));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(
            HandlerMethodValidationException exception
    ) {
        List<ValidationErrorDetail> details = new ArrayList<>();

        for (ParameterValidationResult result : exception.getParameterValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                details.addAll(bindingDetails(parameterErrors));
                continue;
            }

            String field = parameterName(result.getMethodParameter());
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                details.add(new ValidationErrorDetail(field, safeValidationReason()));
            }
        }

        for (MessageSourceResolvable error : exception.getCrossParameterValidationResults()) {
            details.add(new ValidationErrorDetail("$", safeValidationReason()));
        }

        return validationResponse(details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception
    ) {
        List<ValidationErrorDetail> details = exception.getConstraintViolations().stream()
                .map(this::constraintDetail)
                .toList();
        return validationResponse(details);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatch(
            MethodArgumentTypeMismatchException exception
    ) {
        return validationResponse(List.of(
                new ValidationErrorDetail(exception.getName(), INVALID_VALUE_REASON)
        ));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestParameter(
            MissingServletRequestParameterException exception
    ) {
        return validationResponse(List.of(
                new ValidationErrorDetail(exception.getParameterName(), REQUIRED_VALUE_REASON)
        ));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestHeader(
            MissingRequestHeaderException exception
    ) {
        return validationResponse(List.of(
                new ValidationErrorDetail(exception.getHeaderName(), REQUIRED_VALUE_REASON)
        ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableMessage() {
        return response(CommonErrorCode.MALFORMED_REQUEST);
    }

    /** multipart 파싱은 컨트롤러 진입 전 발생하므로 전역 경계에서 Store 이미지 크기 계약으로 변환한다. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceeded() {
        return response(StoreErrorCode.PUBLIC_IMAGE_SIZE_EXCEEDED);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound() {
        return response(CommonErrorCode.ENDPOINT_NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception
    ) {
        return response(CommonErrorCode.METHOD_NOT_ALLOWED, exception.getHeaders());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception
    ) {
        return response(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE, exception.getHeaders());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception) {
        log.error("Unhandled exception", exception);
        return response(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    private List<ValidationErrorDetail> bindingDetails(Errors bindingResult) {
        List<ValidationErrorDetail> details = new ArrayList<>();
        for (FieldError error : bindingResult.getFieldErrors()) {
            details.add(new ValidationErrorDetail(error.getField(), safeValidationReason()));
        }
        for (ObjectError error : bindingResult.getGlobalErrors()) {
            details.add(new ValidationErrorDetail("$", safeValidationReason()));
        }
        return normalize(details);
    }

    private ValidationErrorDetail constraintDetail(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int separator = path.lastIndexOf('.');
        String field = separator >= 0 ? path.substring(separator + 1) : path;
        return new ValidationErrorDetail(field.isBlank() ? "$" : field, INVALID_VALUE_REASON);
    }

    private String parameterName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null) {
            return firstNonBlank(requestParam.name(), requestParam.value(), parameter.getParameterName());
        }

        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        if (pathVariable != null) {
            return firstNonBlank(pathVariable.name(), pathVariable.value(), parameter.getParameterName());
        }

        RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
        if (requestHeader != null) {
            return firstNonBlank(requestHeader.name(), requestHeader.value(), parameter.getParameterName());
        }

        return firstNonBlank(parameter.getParameterName());
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "$";
    }

    private String safeValidationReason() {
        return INVALID_VALUE_REASON;
    }

    private ResponseEntity<ErrorResponse> validationResponse(List<ValidationErrorDetail> details) {
        CommonErrorCode errorCode = CommonErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ErrorResponse.of(errorCode, normalize(details)));
    }

    private List<ValidationErrorDetail> normalize(List<ValidationErrorDetail> details) {
        return details.stream()
                .filter(detail -> detail.field() != null && !detail.field().isBlank())
                .filter(detail -> detail.reason() != null && !detail.reason().isBlank())
                .distinct()
                .sorted(DETAIL_ORDER)
                .toList();
    }

    private ResponseEntity<ErrorResponse> response(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ErrorResponse.from(errorCode));
    }

    private ResponseEntity<ErrorResponse> response(ErrorCode errorCode, HttpHeaders headers) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .headers(headers)
                .body(ErrorResponse.from(errorCode));
    }
}
