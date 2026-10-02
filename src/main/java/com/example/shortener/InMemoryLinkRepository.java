package com.example.shortener;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Repository;

@Repository
public class InMemoryLinkRepository implements LinkRepository {

    private final ConcurrentMap<String, Link> links = new ConcurrentHashMap<>();

    @Override
    public boolean saveIfAbsent(Link link) {
        return links.putIfAbsent(link.code(), link) == null;
    }

    @Override
    public Optional<Link> findByCode(String code) {
        return Optional.ofNullable(links.get(code));
    }

    @Override
    public boolean incrementClicks(String code) {
        Link link = links.get(code);
        if (link == null) {
            return false;
        }
        link.clicks().incrementAndGet();
        return true;
    }
}
