package com.harshpahurkar.rag.document;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.harshpahurkar.rag.security.Roles;

import jakarta.validation.constraints.Size;

/** Upload and delete are ADMIN-only, here and in SecurityConfig's URL rules; listing is filtered to the caller's roles. */
@RestController
@RequestMapping("/api/documents")
class DocumentController {

	private final IngestionService ingestion;

	DocumentController(IngestionService ingestion) {
		this.ingestion = ingestion;
	}

	@GetMapping
	List<DocumentView> list(Authentication authentication) {
		return ingestion.listReadable(Roles.of(authentication));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize("hasRole('ADMIN')")
	DocumentView upload(@RequestParam MultipartFile file, @RequestParam(required = false) @Size(max = 200) String title,
			@RequestParam(required = false) List<String> allowedRoles, Authentication authentication)
			throws IOException {
		String filename = StringUtils.getFilename(file.getOriginalFilename());
		if (!StringUtils.hasText(filename)) {
			filename = "upload";
		}
		if (!StringUtils.hasText(title)) {
			title = StringUtils.stripFilenameExtension(filename);
		}
		try (InputStream in = file.getInputStream()) {
			return ingestion.ingest(title.strip(), filename, file.getContentType(), in, allowedRoles,
					authentication.getName());
		}
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasRole('ADMIN')")
	void delete(@PathVariable long id) {
		ingestion.delete(id);
	}

}
