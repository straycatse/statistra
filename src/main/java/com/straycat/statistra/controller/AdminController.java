package com.straycat.statistra.controller;

import com.straycat.statistra.dto.CreateOrganizationRequest;
import com.straycat.statistra.dto.OrganizationCreatedResponse;
import com.straycat.statistra.dto.OrganizationResponse;
import com.straycat.statistra.repository.OrganizationRepository;
import com.straycat.statistra.service.OrganizationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Operator API for provisioning tenants. Guarded by
 * {@link com.straycat.statistra.security.AdminTokenFilter}.
 */
@RestController
@RequestMapping("/admin/organizations")
public class AdminController {

    private final OrganizationService organizationService;
    private final OrganizationRepository organizationRepository;

    public AdminController(OrganizationService organizationService,
                           OrganizationRepository organizationRepository) {
        this.organizationService = organizationService;
        this.organizationRepository = organizationRepository;
    }

    @PostMapping
    public ResponseEntity<OrganizationCreatedResponse> create(
            @Valid @RequestBody CreateOrganizationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(organizationService.create(request.name()));
    }

    /** Lists organizations. Never includes key material, only the prefix. */
    @GetMapping
    public List<OrganizationResponse> list() {
        return organizationRepository.findAll().stream()
                .map(OrganizationResponse::from)
                .toList();
    }

    @PostMapping("/{id}/rotate-key")
    public OrganizationCreatedResponse rotateKey(@PathVariable Long id) {
        return organizationService.rotateKey(id);
    }
}
