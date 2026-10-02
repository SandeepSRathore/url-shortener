package com.example.shortener;

import java.util.Optional;

public interface LinkRepository {

    /** Atomically stores the link if its code is unused. Returns false if the code already exists. */
    boolean saveIfAbsent(Link link);

    Optional<Link> findByCode(String code);

    /** Atomically adds one click. Returns false if the code is unknown. */
    boolean incrementClicks(String code);
}
