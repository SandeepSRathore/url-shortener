package com.example.shortener;

/** Produces candidate short codes. Callers must handle collisions; generators don't check uniqueness. */
@FunctionalInterface
public interface CodeGenerator {

    String generate();
}
