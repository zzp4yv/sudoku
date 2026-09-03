package com.exemplo.storyboard.controller;

import com.exemplo.storyboard.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Garante que qualquer erro não tratado explicitamente pelo SummaryController
 * ainda assim resulte em uma resposta JSON limpa e amigável, nunca em uma
 * stack trace bruta. Escopado apenas a esse controller para não interceptar
 * erros de resolução de recursos estáticos (ex.: favicon.ico ausente deve
 * continuar respondendo 404, não 500).
 */
@RestControllerAdvice(assignableTypes = SummaryController.class)
public class GlobalExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedJson(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("Requisição inválida: corpo JSON malformado."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Ocorreu um erro inesperado no servidor. Tente novamente."));
    }
}
