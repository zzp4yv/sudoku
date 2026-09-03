package com.exemplo.storyboard.controller;

import com.exemplo.storyboard.dto.ErrorResponse;
import com.exemplo.storyboard.dto.SummarizeRequest;
import com.exemplo.storyboard.service.OpenAiSummaryService;
import com.exemplo.storyboard.service.SummaryGenerationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Function;

/**
 * Endpoints REST usados pelo frontend, via IA:
 * <ul>
 *   <li>{@code POST /api/correct}: corrige um trecho de transcrição recém-reconhecido
 *       (chamado a cada pausa da fala);</li>
 *   <li>{@code POST /api/summarize}: gera um card de storyboard (título + vários ícones)
 *       a partir de uma cena mais ampla de texto já corrigido.</li>
 * </ul>
 */
@RestController
public class SummaryController {

    private final OpenAiSummaryService summaryService;

    public SummaryController(OpenAiSummaryService summaryService) {
        this.summaryService = summaryService;
    }

    @PostMapping("/api/correct")
    public ResponseEntity<?> correct(@RequestBody(required = false) SummarizeRequest request) {
        return handle(request, summaryService::correct);
    }

    @PostMapping("/api/summarize")
    public ResponseEntity<?> summarize(@RequestBody(required = false) SummarizeRequest request) {
        return handle(request, summaryService::summarizeScene);
    }

    private <T> ResponseEntity<?> handle(SummarizeRequest request, Function<String, T> action) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("O texto do trecho não pode estar vazio."));
        }

        try {
            return ResponseEntity.ok(action.apply(request.text()));
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
