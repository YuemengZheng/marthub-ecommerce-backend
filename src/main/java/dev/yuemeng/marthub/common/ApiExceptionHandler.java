package dev.yuemeng.marthub.common;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
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

    /**
     * The residue after the order path has had its turn. A constraint violation is normally resolved
     * there -- the row is read back and the caller gets their real order id with a 200 -- so this
     * only fires when the id could not be recovered, which means the constraint refused something
     * other than a duplicate order. Still the constraint doing its job rather than the server
     * breaking, hence 409 and not the 500 it used to be.
     */
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ApiError> alreadyBought(DuplicateKeyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("ALREADY_BOUGHT", "already purchased"));
    }

    /**
     * The caller already has an attempt in flight. Not a malformed request -- there is nothing for
     * the client to fix -- and not a rate limit either, so it gets the status that says "this
     * collides with state that already exists".
     */
    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiError> conflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(e.code(), e.getMessage()));
    }

    /**
     * MySQL error 1205, a row-lock wait that ran out. It exists as a visible outcome because the
     * wait is deliberately capped at 3s per connection: the processing lease can only be given a
     * bounded TTL if the transaction it covers cannot outlive it, and InnoDB's default 50s wait
     * would let exactly that happen. So this is contention being reported rather than absorbed, and
     * the honest answer is "come back", not "your request was wrong".
     */
    @ExceptionHandler(CannotAcquireLockException.class)
    ResponseEntity<ApiError> contended(CannotAcquireLockException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(new ApiError("CONTENDED", "too much contention on this item, retry shortly"));
    }

    private ResponseEntity<ApiError> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(new ApiError("DEPENDENCY_UNAVAILABLE", "cache unavailable, retry shortly"));
    }
}
