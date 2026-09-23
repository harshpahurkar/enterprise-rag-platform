package com.harshpahurkar.rag.search;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.harshpahurkar.rag.AppProperties;
import com.harshpahurkar.rag.search.Retriever.RetrievedChunk;
import com.harshpahurkar.rag.security.Roles;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Raw semantic search, no LLM: the caller's top-k readable chunks with scores. */
@RestController
@RequestMapping("/api/search")
class SearchController {

	record SearchRequest(@NotBlank @Size(max = 1000) String query, @Min(1) @Max(50) Integer k) {
	}

	record SearchResponse(List<RetrievedChunk> results, long retrievalMs) {
	}

	private final Retriever retriever;

	private final AppProperties props;

	SearchController(Retriever retriever, AppProperties props) {
		this.retriever = retriever;
		this.props = props;
	}

	@PostMapping
	SearchResponse search(@Valid @RequestBody SearchRequest req, Authentication authentication) {
		int k = req.k() != null ? req.k() : props.rag().searchK();
		Retriever.Retrieval r = retriever.retrieve(req.query(), Roles.of(authentication), k);
		return new SearchResponse(r.chunks(), r.retrievalMs());
	}

}
