package com.exemplo.storyboard.dto;

/**
 * Resposta de sucesso de POST /api/summarize: um "card" do storyboard.
 *
 * @param title         legenda curta (até 6 palavras) para o quadro do storyboard
 * @param emoji         um único emoji usado como pictograma grande (o "desenho" do quadro)
 * @param correctedText o trecho de transcrição com prováveis erros de reconhecimento de fala
 *                      corrigidos, preservando o sentido original
 */
public record SummarizeResponse(String title, String emoji, String correctedText) {
}
