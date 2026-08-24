package dev.yuemeng.marthub.common;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * What a caller sees for each failure the application can produce. These were being left to
 * inference, and inference had them wrong: everything that was not a bad request came back as a
 * 500, which sends an operator looking at the code even when the code is fine.
 *
 * <p>Which exception belongs to which Redis failure mode is pinned separately by
 * {@code RedisFailureModeTest}. This test only fixes what each one turns into.
 */
class ApiExceptionHandlerContractTest {

    @RestController
    static class Failing {
        @GetMapping("/boom/connection")
        String connectionRefused() { throw new RedisConnectionFailureException("down"); }

        @GetMapping("/boom/timeout")
        String stalled() { throw new QueryTimeoutException("too slow"); }

        @GetMapping("/boom/duplicate")
        String alreadyBought() { throw new DuplicateKeyException("uq_user_item"); }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Failing())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void aDeadRedisIsServiceUnavailableAndSaysWhenToComeBack() throws Exception {
        mvc.perform(get("/boom/connection"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void aSlowRedisIsTreatedTheSameAsADeadOne() throws Exception {
        // Before this mapping existed a stalled Redis came back as a 500, so the more common
        // failure was the one that looked like a code defect.
        mvc.perform(get("/boom/timeout"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void buyingTheSameItemTwiceIsAConflictNotAServerError() throws Exception {
        // Retiring the token closes the ordinary path, but two requests can still clear admission
        // together and let the unique constraint pick a winner. The loser is a conflict: the
        // constraint did its job, so nothing here is the server malfunctioning.
        mvc.perform(get("/boom/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_BOUGHT"));
    }
}
