package com.example.shortener;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;

@Service
public class LinkService {

    static final int MAX_GENERATION_ATTEMPTS = 5;

    private final LinkRepository repository;
    private final CodeGenerator codeGenerator;
    private final Clock clock;

    public LinkService(LinkRepository repository, CodeGenerator codeGenerator, Clock clock) {
        this.repository = repository;
        this.codeGenerator = codeGenerator;
        this.clock = clock;
    }

    /** Creates a link. A null alias means "generate a code"; any other value must be a valid alias. */
    public Link create(String url, String alias) {
        LinkValidator.validateUrl(url);
        Instant now = clock.instant();

        if (alias != null) {
            LinkValidator.validateAlias(alias);
            Link link = Link.create(alias, url, now);
            if (!repository.saveIfAbsent(link)) {
                throw new AliasTakenException(alias);
            }
            return link;
        }

        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            Link link = Link.create(codeGenerator.generate(), url, now);
            if (repository.saveIfAbsent(link)) {
                return link;
            }
        }
        throw new CodeGenerationException(MAX_GENERATION_ATTEMPTS);
    }

    /** Returns the target URL for a code and records one click. */
    public String resolve(String code) {
        Link link = find(code);
        repository.incrementClicks(code);
        return link.url();
    }

    /** Returns a snapshot of a link's stats. Does not record a click. */
    public LinkStats stats(String code) {
        return LinkStats.of(find(code));
    }

    private Link find(String code) {
        return repository.findByCode(code).orElseThrow(() -> new LinkNotFoundException(code));
    }
}
