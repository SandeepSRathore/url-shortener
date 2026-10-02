package com.example.shortener;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
public class LinkController {

    private final LinkService linkService;

    public LinkController(LinkService linkService) {
        this.linkService = linkService;
    }

    @PostMapping("/api/links")
    public ResponseEntity<LinkResponse> create(@RequestBody CreateLinkRequest request) {
        Link link = linkService.create(request.url(), request.alias());

        String shortUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/{code}").buildAndExpand(link.code()).toUriString();
        URI location = UriComponentsBuilder.fromPath("/api/links/{code}")
                .buildAndExpand(link.code()).toUri();

        return ResponseEntity.created(location)
                .body(new LinkResponse(link.code(), shortUrl, link.url(), link.createdAt()));
    }
}
