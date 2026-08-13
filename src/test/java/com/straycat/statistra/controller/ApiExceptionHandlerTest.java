package com.straycat.statistra.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Status codes for requests Spring MVC rejects before a handler ever runs.
 *
 * <p>These are worth pinning because a {@code @RestControllerAdvice} with a
 * catch-all {@code Exception} handler silently outranks Spring's own resolver:
 * {@code ExceptionHandlerExceptionResolver} is consulted first, so without
 * explicit handlers every framework-level rejection collapses into a 500 and a
 * client cannot tell "you sent something wrong" from "we broke".
 */
class ApiExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ProbeController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void unparseableTimestampIsABadRequest() throws Exception {
        mockMvc.perform(get("/probe").param("from", "not-a-timestamp"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void missingRequiredParameterIsABadRequest() throws Exception {
        mockMvc.perform(get("/probe/required"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void wrongMethodIsMethodNotAllowed() throws Exception {
        mockMvc.perform(post("/probe"))
                .andExpect(status().isMethodNotAllowed());
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe")
        String probe(@RequestParam(required = false) Instant from) {
            return "ok";
        }

        @GetMapping("/probe/required")
        String required(@RequestParam String mandatory) {
            return "ok";
        }
    }
}
