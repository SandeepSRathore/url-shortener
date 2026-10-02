package com.example.shortener;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps domain exceptions to RFC 7807 ProblemDetail responses. Extending
 * ResponseEntityExceptionHandler also turns Spring MVC errors (e.g. malformed JSON) into ProblemDetail.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(InvalidLinkException.class)
    ProblemDetail handleInvalidLink(InvalidLinkException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid link", e);
    }

    @ExceptionHandler(AliasTakenException.class)
    ProblemDetail handleAliasTaken(AliasTakenException e) {
        return problem(HttpStatus.CONFLICT, "Alias already taken", e);
    }

    @ExceptionHandler(LinkNotFoundException.class)
    ProblemDetail handleLinkNotFound(LinkNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Link not found", e);
    }

    @ExceptionHandler(CodeGenerationException.class)
    ProblemDetail handleCodeGeneration(CodeGenerationException e) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Code generation failed", e);
    }

    private static ProblemDetail problem(HttpStatus status, String title, RuntimeException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        problem.setTitle(title);
        return problem;
    }
}
