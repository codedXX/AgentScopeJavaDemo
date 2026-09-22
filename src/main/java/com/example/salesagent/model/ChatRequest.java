package com.example.salesagent.model;
import jakarta.validation.constraints.*;
public record ChatRequest(@Pattern(regexp = "[A-Za-z0-9-]{1,64}") String sessionId,
                          @NotBlank @Size(max = 2000) String message) {}
