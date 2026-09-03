package com.exemplo.storyboard.dto;

/**
 * Corpo da requisição de POST /api/summarize.
 *
 * @param text trecho de transcrição (fala) a ser resumido
 */
public record SummarizeRequest(String text) {
}
