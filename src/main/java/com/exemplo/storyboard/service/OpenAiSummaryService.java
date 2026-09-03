package com.exemplo.storyboard.service;

import com.exemplo.storyboard.dto.CorrectResponse;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Serviço que fala com a API de Chat Completions da OpenAI para dois fins:
 * <ul>
 *   <li>{@link #correct(String)}: corrige rapidamente um trecho recém-reconhecido
 *       (chamado a cada pausa da fala, para manter a transcrição ao vivo legível);</li>
 *   <li>{@link #summarizeScene(String)}: a partir de uma "cena" mais ampla (vários
 *       trechos já corrigidos e acumulados), gera um card de storyboard com vários
 *       ícones representando as diferentes ações/ideias mencionadas.</li>
 * </ul>
 */
@Service
public class OpenAiSummaryService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiSummaryService.class);

    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String MODEL = "gpt-4o-mini";
    private static final int CORRECT_MAX_TOKENS = 300;
    private static final int SCENE_MAX_TOKENS = 300;
    private static final String DEFAULT_ICON = "📝";

    private static final String CORRECTION_SYSTEM_PROMPT = """
            Você recebe um trecho bruto de transcrição de fala (speech-to-text) de uma palestra em português.
            O reconhecimento de fala erra bastante (troca palavras parecidas, ignora pontuação, junta frases),
            então corrija esses erros.

            Responda APENAS com um objeto JSON compacto, sem markdown, sem crases, sem texto antes ou depois,
            exatamente no seguinte formato:
            {"correctedText": "..."}

            Regras:
            - "correctedText": o mesmo trecho, com prováveis erros de reconhecimento de fala corrigidos
              (palavras mal reconhecidas, pontuação, capitalização). Não parafraseie nem resuma — preserve o
              conteúdo e o sentido originais o máximo possível, só torne o texto coerente e legível.
            - Não inclua nenhum texto fora do objeto JSON.
            """;

    private static final String SCENE_SYSTEM_PROMPT = """
            Você recebe um trecho (já corrigido) de transcrição de fala de uma palestra em português,
            cobrindo uma parte da fala do(a) palestrante ao longo de alguns segundos.

            Sua tarefa é identificar pelo menos 3 ações, ideias ou tópicos distintos mencionados nesse
            trecho e representar cada um como um único emoji, formando juntos um pequeno quadro de
            storyboard com várias cenas.

            Responda APENAS com um objeto JSON compacto, sem markdown, sem crases, sem texto antes ou depois,
            exatamente no seguinte formato:
            {"title": "...", "icons": ["...", "...", "..."]}

            Regras:
            - "icons": um array com pelo menos 3 emojis (até 5), cada um representando visualmente uma
              ação/ideia/tópico diferente mencionado no trecho, na ordem aproximada em que foram ditos.
              Evite repetir o mesmo emoji. Se o trecho realmente só tiver 1 ou 2 ideias distintas e não
              der para chegar a 3 sem forçar, use o máximo de emojis distintos e relevantes que fizer
              sentido (mínimo 2).
            - "title": uma legenda curta (no máximo 8 palavras) para o conjunto do trecho, em português.
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
     * Corrige um trecho de transcrição recém-reconhecido (chamado a cada pausa da fala).
     *
     * @param text trecho bruto a corrigir
     * @return o trecho corrigido
     * @throws SummaryGenerationException se a chave de API não estiver configurada, se a chamada
     *                                     à API da OpenAI falhar, ou se a resposta do modelo
     *                                     não puder ser interpretada
     */
    public CorrectResponse correct(String text) {
        JsonNode node = callModel(CORRECTION_SYSTEM_PROMPT, text, CORRECT_MAX_TOKENS);
        JsonNode correctedNode = node.path("correctedText");
        String corrected = correctedNode.isTextual() && !correctedNode.asText().isBlank()
                ? correctedNode.asText()
                : text;
        return new CorrectResponse(corrected);
    }

    /**
     * Gera um card de storyboard (título + vários ícones) a partir de uma cena mais ampla:
     * o texto já corrigido de vários trechos acumulados, com conteúdo suficiente para
     * identificar múltiplas ações/ideias distintas.
     *
     * @param correctedSceneText texto já corrigido da cena a resumir visualmente
     * @return o card gerado, com pelo menos 2 ícones
     * @throws SummaryGenerationException se a chave de API não estiver configurada, se a chamada
     *                                     à API da OpenAI falhar, ou se a resposta do modelo
     *                                     não puder ser interpretada
     */
    public SummarizeResponse summarizeScene(String correctedSceneText) {
        JsonNode node = callModel(SCENE_SYSTEM_PROMPT, correctedSceneText, SCENE_MAX_TOKENS);

        JsonNode titleNode = node.path("title");
        String title = titleNode.isTextual() ? titleNode.asText() : "";

        List<String> icons = new ArrayList<>();
        JsonNode iconsNode = node.path("icons");
        if (iconsNode.isArray()) {
            for (JsonNode icon : iconsNode) {
                if (icon.isTextual() && !icon.asText().isBlank()) {
                    icons.add(icon.asText());
                }
            }
        }
        if (icons.isEmpty()) {
            icons.add(DEFAULT_ICON);
        }

        return new SummarizeResponse(title, icons);
    }

    /**
     * Chama a API de Chat Completions da OpenAI com o prompt de sistema e o texto do
     * usuário informados, e devolve o corpo JSON já decodificado da resposta do modelo
     * (depois de remover eventuais blocos de código markdown ao redor).
     */
    private JsonNode callModel(String systemPrompt, String userText, int maxTokens) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new SummaryGenerationException(
                    "Chave da API OpenAI não configurada (defina a variável de ambiente OPENAI_API_KEY)");
        }

        String requestBody = buildRequestBody(systemPrompt, userText, maxTokens);

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
        String cleaned = stripCodeFences(modelText);
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            throw new SummaryGenerationException(
                    "Não foi possível interpretar a resposta gerada pela IA.", e);
        }
    }

    private String buildRequestBody(String systemPrompt, String userText, int maxTokens) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", MODEL);
        root.put("max_tokens", maxTokens);

        ObjectNode responseFormat = root.putObject("response_format");
        responseFormat.put("type", "json_object");

        ArrayNode messages = root.putArray("messages");

        ObjectNode systemMessage = messages.addObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", systemPrompt);

        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", userText);

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
