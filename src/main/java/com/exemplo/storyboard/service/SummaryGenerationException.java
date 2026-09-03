package com.exemplo.storyboard.service;

/**
 * Lançada quando não é possível gerar o resumo de um trecho de transcrição
 * (chave de API ausente, erro na chamada à API da Anthropic, ou resposta
 * do modelo em formato inesperado). A mensagem é sempre amigável e em
 * português, pronta para ser devolvida ao cliente dentro de um ErrorResponse.
 */
public class SummaryGenerationException extends RuntimeException {

    public SummaryGenerationException(String message) {
        super(message);
    }

    public SummaryGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
