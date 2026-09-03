package com.exemplo.storyboard.dto;

/**
 * Resposta de sucesso de POST /api/summarize: um "card" do storyboard.
 *
 * @param title   título curto (até 6 palavras) resumindo a ideia central do trecho
 * @param emoji   um único emoji representativo do trecho
 * @param summary resumo de 1-2 frases em português do que foi dito
 */
public record SummarizeResponse(String title, String emoji, String summary) {
}
