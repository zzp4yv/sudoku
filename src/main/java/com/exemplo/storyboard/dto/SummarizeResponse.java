package com.exemplo.storyboard.dto;

import java.util.List;

/**
 * Resposta de sucesso de POST /api/summarize: um "card" do storyboard,
 * cobrindo uma cena mais ampla (vários trechos já corrigidos, acumulados
 * até haver conteúdo suficiente).
 *
 * @param title legenda curta para o quadro do storyboard
 * @param icons pelo menos 2 (idealmente 3 ou mais) emojis, cada um representando
 *              visualmente uma ação/ideia distinta mencionada na cena
 */
public record SummarizeResponse(String title, List<String> icons) {
}
