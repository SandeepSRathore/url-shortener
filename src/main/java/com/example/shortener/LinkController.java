package com.example.shortener;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
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

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        return found(linkService.resolve(code));
    }

    /** HEAD gets the same redirect but is not a click: link checkers and previews must not inflate stats. */
    @RequestMapping(path = "/{code}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> redirectHead(@PathVariable String code) {
        return found(linkService.stats(code).url());
    }

    @GetMapping("/api/links/{code}")
    public LinkStats stats(@PathVariable String code) {
        return linkService.stats(code);
    }

    private static ResponseEntity<Void> found(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }
}
