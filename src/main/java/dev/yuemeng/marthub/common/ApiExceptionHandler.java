package dev.yuemeng.marthub.common;

import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<ApiError> badRequest(BadRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(e.code(), e.getMessage()));
    }

    /**
     * Redis being unreachable already failed closed, in the sense that admission runs before the
     * order is written and the exception aborted the request short of MySQL -- but it surfaced as
     * a 500, which reads as "this request is broken" rather than "the dependency is down, come
     * back". 503 with Retry-After says which one it is, and keeps a cache outage from being
     * indistinguishable from a bug in the logs.
     *
     * <p>Failing closed is the deliberate choice here: if a Redis outage let admission through,
     * the entire flash-sale burst would arrive at MySQL with nothing in front of it.
     */
    @ExceptionHandler(RedisConnectionFailureException.class)
    ResponseEntity<ApiError> redisUnavailable(RedisConnectionFailureException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(new ApiError("DEPENDENCY_UNAVAILABLE", "cache unavailable, retry shortly"));
    }
}
