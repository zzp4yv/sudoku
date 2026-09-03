package com.exemplo.storyboard.dto;

/**
 * Corpo da requisição de POST /api/correct e POST /api/summarize.
 *
 * @param text trecho de transcrição (fala) a ser corrigido ou resumido
 */
public record SummarizeRequest(String text) {
}
