package com.harshpahurkar.rag.document;

import java.time.Instant;
import java.util.List;

public record DocumentView(long id, String title, String filename, String contentType, List<String> allowedRoles,
		String uploadedBy, Instant createdAt, int chunkCount) {
}
