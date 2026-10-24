package com.flicknames.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the full application context against the default in-memory H2 database,
 * so CI catches wiring, mapping and startup errors before they reach Railway.
 */
@SpringBootTest
class FlicknamesServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
