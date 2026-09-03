package com.exemplo.storyboard.service;

import com.exemplo.storyboard.dto.SummarizeResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Serviço responsável por transformar um trecho de transcrição em um "card"
 * de storyboard (título curto + emoji + resumo), usando a API de Mensagens
 * da Anthropic.
 */
@Service
public class AnthropicSummaryService {

    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String MODEL = "claude-sonnet-5";
    private static final int MAX_TOKENS = 300;

    private static final String SYSTEM_PROMPT = """
            Você recebe um trecho bruto de transcrição de fala (speech-to-text) de uma palestra em português.
            Responda APENAS com um objeto JSON compacto, sem markdown, sem crases, sem texto antes ou depois,
            exatamente no seguinte formato:
            {"title": "...", "emoji": "...", "summary": "..."}

            Regras:
            - "title": título curto (no máximo 6 palavras) resumindo a ideia central do trecho, em português.
            - "emoji": um único emoji que represente bem o trecho.
            - "summary": 1 a 2 frases curtas em português capturando o que foi dito.
            - Não inclua nenhum texto fora do objeto JSON.
            """;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${anthropic.api.key:}")
    private String apiKey;

    public AnthropicSummaryService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * Gera um card de storyboard (título, emoji e resumo) a partir de um trecho de transcrição.
     *
     * @param text trecho de transcrição a resumir
     * @return o card gerado
     * @throws SummaryGenerationException se a chave de API não estiver configurada, se a chamada
     *                                     à API da Anthropic falhar, ou se a resposta do modelo
     *                                     não puder ser interpretada
     */
    public SummarizeResponse summarize(String text) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new SummaryGenerationException(
                    "Chave da API Anthropic não configurada (defina a variável de ambiente ANTHROPIC_API_KEY)");
        }

        String requestBody = buildRequestBody(text);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ANTHROPIC_API_URL))
                .timeout(Duration.ofSeconds(30))
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new SummaryGenerationException(
                    "Falha ao conectar com a API da Anthropic. Verifique a conexão de rede e tente novamente.", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SummaryGenerationException(
                    "A API da Anthropic retornou um erro (status " + response.statusCode()
                            + "). Verifique a chave de API e tente novamente.");
        }

        String modelText = extractModelText(response.body());
        return parseSummary(modelText);
    }

    private String buildRequestBody(String text) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", MODEL);
        root.put("max_tokens", MAX_TOKENS);
        root.put("system", SYSTEM_PROMPT);

        ArrayNode messages = root.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", text);

        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new SummaryGenerationException("Falha interna ao preparar a requisição para a IA.", e);
        }
    }

    private String extractModelText(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode content = root.path("content");
            if (content.isArray() && content.size() > 0) {
                JsonNode firstBlock = content.get(0);
                JsonNode textNode = firstBlock.path("text");
                if (textNode.isTextual()) {
                    return textNode.asText();
                }
            }
            throw new SummaryGenerationException(
                    "Resposta inesperada da API da Anthropic (sem conteúdo de texto).");
        } catch (SummaryGenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new SummaryGenerationException(
                    "Não foi possível interpretar a resposta da API da Anthropic.", e);
        }
    }

    private SummarizeResponse parseSummary(String modelText) {
        String cleaned = stripCodeFences(modelText);
        try {
            return objectMapper.readValue(cleaned, SummarizeResponse.class);
        } catch (Exception e) {
            throw new SummaryGenerationException(
                    "Não foi possível interpretar o resumo gerado pela IA.", e);
        }
    }

    /**
     * Remove blocos de código markdown (```json ... ``` ou ``` ... ```) que o modelo
     * eventualmente inclua ao redor do JSON, mesmo quando instruído a não fazê-lo.
     */
    private String stripCodeFences(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            int fenceEnd = trimmed.lastIndexOf("```");
            if (fenceEnd != -1) {
                trimmed = trimmed.substring(0, fenceEnd);
            }
        }
        return trimmed.trim();
    }
}
