package com.example.shortener;

/** Every attempt to generate an unused code collided. Maps to HTTP 500. */
public class CodeGenerationException extends RuntimeException {

    public CodeGenerationException(int attempts) {
        super("could not generate a unique code after " + attempts + " attempts");
    }
}
