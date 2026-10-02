package com.example.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RandomCodeGeneratorTest {

    private final RandomCodeGenerator generator = new RandomCodeGenerator();

    @Test
    @DisplayName("R1: generated codes are 7 base62 characters")
    void generatesSevenCharBase62Codes() {
        for (int i = 0; i < 1000; i++) {
            assertThat(generator.generate()).matches("[0-9A-Za-z]{7}");
        }
    }

    @Test
    void generatesDifferentCodesAcrossCalls() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            codes.add(generator.generate());
        }
        // 1000 draws from 62^7 values: a repeat has probability ~1e-7.
        assertThat(codes).hasSize(1000);
    }
}
