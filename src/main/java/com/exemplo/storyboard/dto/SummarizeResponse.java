package com.exemplo.storyboard.dto;

/**
 * Resposta de sucesso de POST /api/summarize: o título e a descrição visual de
 * um card do storyboard, cobrindo uma cena mais ampla (vários trechos já
 * corrigidos, acumulados até haver conteúdo suficiente). A ilustração em si é
 * gerada à parte por POST /api/illustrate, a partir de {@code imagePrompt}.
 *
 * @param title       legenda curta para o quadro do storyboard, em português
 * @param imagePrompt descrição em inglês da cena a ser desenhada, representando
 *                     pelo menos 3 elementos/ações distintos mencionados no trecho
 */
public record SummarizeResponse(String title, String imagePrompt) {
}
