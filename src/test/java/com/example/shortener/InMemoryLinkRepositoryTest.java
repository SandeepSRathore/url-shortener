package com.example.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryLinkRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

    private final InMemoryLinkRepository repository = new InMemoryLinkRepository();

    @Test
    void savedLinkCanBeFoundByCode() {
        Link link = Link.create("abc1234", "https://example.com", NOW);

        assertThat(repository.saveIfAbsent(link)).isTrue();
        assertThat(repository.findByCode("abc1234")).containsSame(link);
    }

    @Test
    void findByUnknownCodeIsEmpty() {
        assertThat(repository.findByCode("missing")).isEmpty();
    }

    @Test
    void saveIfAbsentRejectsExistingCodeAndKeepsOriginal() {
        Link original = Link.create("taken", "https://first.example.com", NOW);
        repository.saveIfAbsent(original);

        boolean saved = repository.saveIfAbsent(Link.create("taken", "https://second.example.com", NOW));

        assertThat(saved).isFalse();
        assertThat(repository.findByCode("taken")).containsSame(original);
    }

    @Test
    void codesAreCaseSensitive() {
        assertThat(repository.saveIfAbsent(Link.create("my-link", "https://a.example.com", NOW))).isTrue();
        assertThat(repository.saveIfAbsent(Link.create("My-Link", "https://b.example.com", NOW))).isTrue();
    }

    @Test
    void incrementClicksOnUnknownCodeReturnsFalse() {
        assertThat(repository.incrementClicks("missing")).isFalse();
    }

    @Test
    void newLinkStartsWithZeroClicks() {
        assertThat(Link.create("abc1234", "https://example.com", NOW).clicks().get()).isZero();
    }

    @Test
    @DisplayName("R9: concurrent saves of the same code - exactly one succeeds")
    void concurrentSavesOfSameCodeExactlyOneWins() throws Exception {
        AtomicInteger counter = new AtomicInteger();

        List<Boolean> results = runConcurrently(50, () -> repository.saveIfAbsent(
                Link.create("same-alias", "https://example.com/" + counter.incrementAndGet(), NOW)));

        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
    }

    @Test
    @DisplayName("R10: N concurrent click increments - clicks equals N")
    void concurrentIncrementsAreAllCounted() throws Exception {
        repository.saveIfAbsent(Link.create("popular", "https://example.com", NOW));

        List<Boolean> results = runConcurrently(100, () -> repository.incrementClicks("popular"));

        assertThat(results).containsOnly(true);
        assertThat(repository.findByCode("popular").orElseThrow().clicks().get()).isEqualTo(100);
    }

    /** Starts all tasks at the same instant (via a latch) to maximise contention. */
    private static List<Boolean> runConcurrently(int threads, Callable<Boolean> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get(5, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
