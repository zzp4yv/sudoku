package com.exemplo.storyboard.dto;

/**
 * Resposta de sucesso de POST /api/illustrate: a ilustração gerada para um
 * card do storyboard.
 *
 * @param imageBase64 imagem PNG codificada em base64 (sem o prefixo "data:")
 */
public record IllustrationResponse(String imageBase64) {
}
