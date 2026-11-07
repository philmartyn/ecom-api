package com.example.ecom.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

final class ProblemDetailResponse {

    private ProblemDetailResponse() {}

    static void write(ObjectMapper objectMapper,
                      HttpServletRequest request,
                      HttpServletResponse response,
                      HttpStatus status,
                      String title,
                      String details) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, details);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

}
