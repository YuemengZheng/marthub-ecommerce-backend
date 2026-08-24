package dev.yuemeng.marthub.common;

import org.springframework.dao.QueryTimeoutException;
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
        return unavailable();
    }

    /**
     * The failure mode the connection exception does not cover, and the more common one in
     * production: the pool is healthy and Redis simply stops answering. Spring translates a
     * command timeout on an established connection to {@link QueryTimeoutException}, not to a
     * connection failure -- a probe against a real server confirmed the split rather than us
     * inferring it from the class hierarchy. Without this mapping that case surfaced as a 500,
     * so "Redis is slow" was indistinguishable from a bug in the logs.
     */
    @ExceptionHandler(QueryTimeoutException.class)
    ResponseEntity<ApiError> redisTooSlow(QueryTimeoutException e) {
        return unavailable();
    }

    private ResponseEntity<ApiError> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(new ApiError("DEPENDENCY_UNAVAILABLE", "cache unavailable, retry shortly"));
    }
}
