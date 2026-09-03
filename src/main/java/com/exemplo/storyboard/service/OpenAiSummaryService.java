package com.exemplo.storyboard.service;

import com.exemplo.storyboard.dto.SummarizeResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Serviço responsável por transformar um trecho de transcrição em um "card"
 * de storyboard (título curto + emoji + resumo), usando a API de Chat
 * Completions da OpenAI.
 */
@Service
public class OpenAiSummaryService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiSummaryService.class);

    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL = "gpt-4o-mini";
    private static final int MAX_TOKENS = 500;

    private static final String SYSTEM_PROMPT = """
            Você recebe um trecho bruto de transcrição de fala (speech-to-text) de uma palestra em português.
            O reconhecimento de fala erra bastante (troca palavras parecidas, ignora pontuação, junta frases),
            então corrija esses erros antes de tudo.

            Responda APENAS com um objeto JSON compacto, sem markdown, sem crases, sem texto antes ou depois,
            exatamente no seguinte formato:
            {"correctedText": "...", "title": "...", "emoji": "..."}

            Regras:
            - "correctedText": o mesmo trecho, mas com prováveis erros de reconhecimento de fala corrigidos
              (palavras mal reconhecidas, pontuação, capitalização). Não parafraseie nem resuma — preserve o
              conteúdo e o sentido originais o máximo possível, só torne o texto coerente e legível.
            - "title": uma legenda curta (no máximo 6 palavras), como a legenda de um quadro de storyboard,
              em português.
            - "emoji": um único emoji que funcione como um pictograma grande representando visualmente a
              cena/ideia do trecho — escolha algo bem ilustrativo, pois ele será exibido em destaque como o
              desenho do quadro.
            - Não inclua nenhum texto fora do objeto JSON.
            """;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${openai.api.key:}")
    private String apiKey;

    public OpenAiSummaryService(ObjectMapper objectMapper) {
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
     *                                     à API da OpenAI falhar, ou se a resposta do modelo
     *                                     não puder ser interpretada
     */
    public SummarizeResponse summarize(String text) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new SummaryGenerationException(
                    "Chave da API OpenAI não configurada (defina a variável de ambiente OPENAI_API_KEY)");
        }

        String requestBody = buildRequestBody(text);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_API_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new SummaryGenerationException(
                    "Falha ao conectar com a API da OpenAI. Verifique a conexão de rede e tente novamente.", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.error("OpenAI API returned status {}: {}", response.statusCode(), response.body());
            String upstreamMessage = extractErrorMessage(response.body());
            String detail = upstreamMessage != null
                    ? upstreamMessage
                    : "verifique a chave de API e tente novamente";
            throw new SummaryGenerationException(
                    "A API da OpenAI retornou um erro (status " + response.statusCode() + "): " + detail);
        }

        String modelText = extractModelText(response.body());
        return parseSummary(modelText);
    }

    private String buildRequestBody(String text) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", MODEL);
        root.put("max_tokens", MAX_TOKENS);

        ObjectNode responseFormat = root.putObject("response_format");
        responseFormat.put("type", "json_object");

        ArrayNode messages = root.putArray("messages");

        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", SYSTEM_PROMPT);

        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", text);

        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new SummaryGenerationException("Falha interna ao preparar a requisição para a IA.", e);
        }
    }

    /**
     * Extrai a mensagem de erro de uma resposta de erro da API da OpenAI
     * (formato {"error": {"message": "...", "type": "...", "code": "..."}}),
     * para que o motivo real (chave inválida, modelo inexistente, corpo malformado etc.)
     * seja logado e reportado em vez de apenas o status HTTP.
     */
    private String extractErrorMessage(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode message = root.path("error").path("message");
            return message.isTextual() ? message.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractModelText(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                JsonNode textNode = choices.get(0).path("message").path("content");
                if (textNode.isTextual()) {
                    return textNode.asText();
                }
            }
            throw new SummaryGenerationException(
                    "Resposta inesperada da API da OpenAI (sem conteúdo de texto).");
        } catch (SummaryGenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new SummaryGenerationException(
                    "Não foi possível interpretar a resposta da API da OpenAI.", e);
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
