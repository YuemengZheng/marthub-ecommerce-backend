package dev.yuemeng.marthub.common;

import org.junit.jupiter.api.Test;
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
 * "What does a caller see when Redis is unavailable" is a contract, and it was being left to
 * inference. Both failure modes have to read as a dependency being down rather than as a bug: a
 * 500 sends an operator looking at the code, which is the wrong place.
 *
 * <p>Which exception belongs to which failure mode is pinned separately by
 * {@code RedisFailureModeTest}. This test only fixes what each one turns into.
 */
class RedisUnavailableContractTest {

    @RestController
    static class Failing {
        @GetMapping("/boom/connection")
        String connectionRefused() { throw new RedisConnectionFailureException("down"); }

        @GetMapping("/boom/timeout")
        String stalled() { throw new QueryTimeoutException("too slow"); }
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
}
