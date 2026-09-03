package com.exemplo.storyboard.dto;

/**
 * Corpo de resposta padrão para erros da API, sempre com mensagem amigável em português.
 *
 * @param error mensagem de erro amigável, pronta para exibição ao usuário
 */
public record ErrorResponse(String error) {
}
