package com.flicknames.service.collector.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flicknames.service.collector.service.DataCollectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Controller for Server-Sent Events (SSE) to broadcast collection progress to admin dashboard.
 */
@RestController
@RequestMapping("/api/v1/admin/collection")
@RequiredArgsConstructor
@Slf4j
public class CollectionSseController {

    private final DataCollectorService collectorService;
    private final ObjectMapper objectMapper;

    // Thread-safe list of active SSE emitters
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /**
     * SSE endpoint - clients connect here to receive real-time progress updates
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamProgress() {
        SseEmitter emitter = new SseEmitter(0L); // No timeout (keep alive with heartbeat)
        emitters.add(emitter);

        log.info("New SSE client connected. Total clients: {}", emitters.size());

        // Send initial connection confirmation
        try {
            Map<String, Object> status = collectorService.getCollectionStatus();
            emitter.send(SseEmitter.event()
                    .name("STATUS")
                    .data(objectMapper.writeValueAsString(status)));
        } catch (IOException e) {
            log.error("Failed to send initial status", e);
        }

        // Handle client disconnect
        emitter.onCompletion(() -> {
            emitters.remove(emitter);
            log.info("SSE client disconnected. Remaining clients: {}", emitters.size());
        });

        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            log.info("SSE client timed out. Remaining clients: {}", emitters.size());
        });

        emitter.onError((e) -> {
            emitters.remove(emitter);
            log.error("SSE error for client. Remaining clients: {}", emitters.size(), e);
        });

        return emitter;
    }

    /**
     * Fire-and-forget endpoint to start collection
     * Returns 202 Accepted immediately, progress available via SSE
     */
    @PostMapping("/start/{year}")
    public ResponseEntity<Map<String, String>> startCollection(
            @PathVariable int year,
            @RequestParam(defaultValue = "false") boolean usOnly,
            @RequestParam(defaultValue = "100") int maxPages) {

        if (collectorService.isCollectionRunning()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Collection already running"));
        }

        // Start collection on new thread (fire-and-forget)
        new Thread(() -> {
            try {
                collectorService.collectYearComprehensive(year, usOnly, maxPages);
            } catch (Exception e) {
                log.error("Collection failed for year {}", year, e);
            }
        }, "collection-" + year).start();

        return ResponseEntity.accepted()
                .body(Map.of("message", String.format("Collection started for year %d", year)));
    }

    /**
     * Listen for collection progress events and broadcast to all SSE clients
     */
    @EventListener
    public void handleProgressEvent(CollectionProgressEvent event) {
        if (emitters.isEmpty()) {
            return; // No clients connected, skip
        }

        try {
            // Build event data
            Map<String, Object> eventData = Map.of(
                    "eventType", event.getEventType().name(),
                    "year", event.getYear() != null ? event.getYear() : "",
                    "strategy", event.getStrategy() != null ? event.getStrategy() : "",
                    "currentPage", event.getCurrentPage() != null ? event.getCurrentPage() : "",
                    "totalPages", event.getTotalPages() != null ? event.getTotalPages() : "",
                    "moviesCollected", event.getMoviesCollected() != null ? event.getMoviesCollected() : 0,
                    "movieTitle", event.getMovieTitle() != null ? event.getMovieTitle() : "",
                    "message", event.getMessage() != null ? event.getMessage() : "",
                    "timestamp", System.currentTimeMillis()
            );

            String jsonData = objectMapper.writeValueAsString(eventData);

            // Broadcast to all connected clients
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event()
                            .name(event.getEventType().name())
                            .data(jsonData));
                } catch (IOException e) {
                    log.warn("Failed to send event to client, removing", e);
                    emitters.remove(emitter);
                    emitter.completeWithError(e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to broadcast progress event", e);
        }
    }

    /**
     * Heartbeat to prevent Railway proxy timeout (every 15 seconds)
     */
    @Scheduled(fixedRate = 15000)
    public void sendHeartbeat() {
        if (emitters.isEmpty()) {
            return;
        }

        try {
            Map<String, Object> heartbeat = Map.of(
                    "type", "heartbeat",
                    "timestamp", System.currentTimeMillis()
            );

            String jsonData = objectMapper.writeValueAsString(heartbeat);

            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("HEARTBEAT")
                            .data(jsonData));
                } catch (IOException e) {
                    log.debug("Failed to send heartbeat to client, removing", e);
                    emitters.remove(emitter);
                    emitter.completeWithError(e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to send heartbeat", e);
        }
    }
}
