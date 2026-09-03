package com.exemplo.storyboard.dto;

/**
 * Resposta de sucesso de POST /api/correct: a versão corrigida de um trecho
 * de transcrição.
 *
 * @param correctedText o trecho com prováveis erros de reconhecimento de fala
 *                      corrigidos, preservando o sentido original
 */
public record CorrectResponse(String correctedText) {
}
