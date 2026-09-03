package com.exemplo.storyboard.controller;

import com.exemplo.storyboard.dto.ErrorResponse;
import com.exemplo.storyboard.dto.SummarizeRequest;
import com.exemplo.storyboard.dto.SummarizeResponse;
import com.exemplo.storyboard.service.AnthropicSummaryService;
import com.exemplo.storyboard.service.SummaryGenerationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint REST usado pelo frontend para transformar trechos de transcrição
 * em cards de storyboard (título + emoji + resumo), via IA.
 */
@RestController
public class SummaryController {

    private final AnthropicSummaryService summaryService;

    public SummaryController(AnthropicSummaryService summaryService) {
        this.summaryService = summaryService;
    }

    @PostMapping("/api/summarize")
    public ResponseEntity<?> summarize(@RequestBody(required = false) SummarizeRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("O texto do trecho não pode estar vazio."));
        }

        try {
            SummarizeResponse result = summaryService.summarize(request.text());
            return ResponseEntity.ok(result);
        } catch (SummaryGenerationException e) {
            HttpStatus status = e.getMessage() != null && e.getMessage().contains("não configurada")
                    ? HttpStatus.BAD_REQUEST
                    : HttpStatus.BAD_GATEWAY;
            return ResponseEntity.status(status).body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new ErrorResponse("Ocorreu um erro inesperado ao gerar o resumo. Tente novamente."));
        }
    }
}
