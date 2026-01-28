package com.flicknames.service.research.controller;

import com.flicknames.service.research.dto.*;
import com.flicknames.service.research.service.NameResearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controller for name research endpoints (public and admin)
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class NameResearchController {

    private final NameResearchService nameResearchService;

    // ==================== PUBLIC ENDPOINTS ====================

    /**
     * Get approved research for a name
     * GET /api/v1/names/{name}/research
     */
    @GetMapping("/names/{name}/research")
    public ResponseEntity<NameResearchDTO> getNameResearch(@PathVariable String name) {
        return nameResearchService.getApprovedResearch(name)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    // ==================== ADMIN ENDPOINTS - IMPORT ====================

    /**
     * Import name research data (creates as PENDING)
     * POST /api/v1/admin/research/import
     */
    @PostMapping("/admin/research/import")
    public ResponseEntity<NameResearchAdminDTO> importResearch(@RequestBody NameResearchImportDTO importDTO) {
        try {
            NameResearchAdminDTO result = nameResearchService.importResearch(importDTO);
            return ResponseEntity.status(HttpStatus.CREATED).body(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get names needing research (by SSA popularity)
     * GET /api/v1/admin/research/needed?limit=50
     */
    @GetMapping("/admin/research/needed")
    public ResponseEntity<List<NameToResearchDTO>> getNamesNeedingResearch(
        @RequestParam(defaultValue = "50") int limit
    ) {
        List<NameToResearchDTO> names = nameResearchService.getNamesNeedingResearch(limit);
        return ResponseEntity.ok(names);
    }

    /**
     * Get research coverage statistics
     * GET /api/v1/admin/research/stats
     */
    @GetMapping("/admin/research/stats")
    public ResponseEntity<ResearchStatsDTO> getResearchStats() {
        ResearchStatsDTO stats = nameResearchService.getResearchStats();
        return ResponseEntity.ok(stats);
    }

    // ==================== ADMIN ENDPOINTS - APPROVAL WORKFLOW ====================

    /**
     * List all PENDING research awaiting approval
     * GET /api/v1/admin/research/pending?page=0&size=20
     */
    @GetMapping("/admin/research/pending")
    public ResponseEntity<Page<NameResearchAdminDTO>> getPendingResearch(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        Page<NameResearchAdminDTO> pending = nameResearchService.getPendingResearch(pageable);
        return ResponseEntity.ok(pending);
    }

    /**
     * View specific research entry for review
     * GET /api/v1/admin/research/{id}
     */
    @GetMapping("/admin/research/{id}")
    public ResponseEntity<NameResearchAdminDTO> getResearchById(@PathVariable Long id) {
        return nameResearchService.getResearchById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Approve research (status -> APPROVED, visible to public)
     * POST /api/v1/admin/research/{id}/approve
     */
    @PostMapping("/admin/research/{id}/approve")
    public ResponseEntity<NameResearchAdminDTO> approveResearch(@PathVariable Long id) {
        try {
            NameResearchAdminDTO result = nameResearchService.approveResearch(id);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Reject research with notes (status -> REJECTED)
     * POST /api/v1/admin/research/{id}/reject
     * Body: { "notes": "Reason for rejection" }
     */
    @PostMapping("/admin/research/{id}/reject")
    public ResponseEntity<NameResearchAdminDTO> rejectResearch(
        @PathVariable Long id,
        @RequestBody Map<String, String> body
    ) {
        try {
            String notes = body.getOrDefault("notes", "");
            NameResearchAdminDTO result = nameResearchService.rejectResearch(id, notes);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Bulk approve multiple entries by IDs
     * POST /api/v1/admin/research/bulk-approve
     * Body: { "ids": [1, 2, 3] }
     */
    @PostMapping("/admin/research/bulk-approve")
    public ResponseEntity<List<NameResearchAdminDTO>> bulkApprove(@RequestBody Map<String, List<Long>> body) {
        List<Long> ids = body.get("ids");
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        List<NameResearchAdminDTO> results = nameResearchService.bulkApprove(ids);
        return ResponseEntity.ok(results);
    }
}
