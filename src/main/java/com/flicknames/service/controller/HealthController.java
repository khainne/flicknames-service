package com.flicknames.service.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Health", description = "Service health check endpoints")
public class HealthController {

    @GetMapping("/health")
    @Operation(
            summary = "Check service health",
            description = "Returns the current health status of the flicknames-service"
    )
    @ApiResponse(responseCode = "200", description = "Service is healthy")
    public Map<String, String> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "flicknames-service");
        return response;
    }
}
