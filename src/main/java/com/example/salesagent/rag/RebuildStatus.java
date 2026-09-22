package com.example.salesagent.rag;

import java.time.Instant;

public record RebuildStatus(boolean ready, long chunkCount, Instant rebuiltAt, String message) {}
