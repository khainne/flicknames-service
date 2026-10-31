package com.flicknames.service.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "admin.api-key=test-key",
        "cors.allowed-origins=https://admin.example"
})
@AutoConfigureMockMvc
class AdminApiKeyFilterTests {

    private static final String WRITE_PATH = "/api/v1/admin/patterns/refresh";

    @Autowired
    private MockMvc mvc;

    @Test
    void writeWithoutKeyIsRejected() throws Exception {
        mvc.perform(post(WRITE_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void writeWithWrongKeyIsRejected() throws Exception {
        mvc.perform(post(WRITE_PATH).header(AdminApiKeyFilter.HEADER, "nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void writeWithKeyIsAllowed() throws Exception {
        mvc.perform(post(WRITE_PATH).header(AdminApiKeyFilter.HEADER, "test-key"))
                .andExpect(status().isOk());
    }

    @Test
    void publicReadNeedsNoKey() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void adminReadWithoutKeyIsRejected() throws Exception {
        mvc.perform(get("/api/v1/admin/db-stats")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminReadWithWrongKeyIsRejected() throws Exception {
        mvc.perform(get("/api/v1/admin/db-stats").header(AdminApiKeyFilter.HEADER, "nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminReadWithKeyIsAllowed() throws Exception {
        mvc.perform(get("/api/v1/admin/db-stats").header(AdminApiKeyFilter.HEADER, "test-key"))
                .andExpect(status().isOk());
    }

    @Test
    void adminProgressStreamNeedsKey() throws Exception {
        mvc.perform(get("/api/v1/admin/collection/stream")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminPathCheckIgnoresPathParameters() {
        assertThat(AdminApiKeyFilter.isAdminPath("/api/v1/admin/db-stats")).isTrue();
        assertThat(AdminApiKeyFilter.isAdminPath("/api/v1/admin")).isTrue();
        assertThat(AdminApiKeyFilter.isAdminPath("/api/v1/administrator")).isFalse();
        assertThat(AdminApiKeyFilter.isAdminPath("/api/v1/names/admin")).isFalse();
    }

    @Test
    void adminReadWithPathParameterStillNeedsKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin;x=1/db-stats");
        request.setServletPath("/api/v1/admin/db-stats");
        assertThat(AdminApiKeyFilter.isAdminPath(request)).isTrue();
    }

    @Test
    void adminReadPreflightFromAdminOriginIsAllowed() throws Exception {
        mvc.perform(options("/api/v1/admin/db-stats")
                        .header("Origin", "https://admin.example")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", AdminApiKeyFilter.HEADER))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://admin.example"));
    }

    @Test
    void adminReadPreflightFromOtherOriginIsRefused() throws Exception {
        mvc.perform(options("/api/v1/admin/db-stats")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", AdminApiKeyFilter.HEADER))
                .andExpect(status().isForbidden());
    }

    @Test
    void debugReadNeedsKey() throws Exception {
        mvc.perform(get("/api/v1/admin/debug/files")).andExpect(status().isUnauthorized());
    }

    @Test
    void readsAreAllowedFromAnyOrigin() throws Exception {
        mvc.perform(get("/api/health").header("Origin", "https://anywhere.example"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"));
    }

    @Test
    void writePreflightFromAdminOriginIsAllowed() throws Exception {
        mvc.perform(options(WRITE_PATH)
                        .header("Origin", "https://admin.example")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", AdminApiKeyFilter.HEADER))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://admin.example"));
    }

    @Test
    void writePreflightFromOtherOriginIsRefused() throws Exception {
        mvc.perform(options(WRITE_PATH)
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectedWriteStillCarriesCorsHeaders() throws Exception {
        mvc.perform(post(WRITE_PATH).header("Origin", "https://admin.example"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://admin.example"));
    }
}
