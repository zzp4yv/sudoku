package com.exemplo.storyboard.service;

import com.exemplo.storyboard.dto.CorrectResponse;
import com.exemplo.storyboard.dto.IllustrationResponse;
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
import java.util.Base64;

/**
 * Serviço que fala com as APIs da OpenAI para três fins:
 * <ul>
 *   <li>{@link #correct(String)}: corrige rapidamente um trecho recém-reconhecido
 *       (chamado a cada pausa da fala, para manter a transcrição ao vivo legível);</li>
 *   <li>{@link #summarizeScene(String)}: a partir de uma "cena" mais ampla (vários
 *       trechos já corrigidos e acumulados), gera o título e a descrição visual
 *       (para ilustração) de um card de storyboard;</li>
 *   <li>{@link #illustrate(String)}: gera a ilustração em si (estilo sketchnote/
 *       graphic recording) a partir dessa descrição visual, via API de imagens.</li>
 * </ul>
 */
@Service
public class OpenAiSummaryService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiSummaryService.class);

    private static final String OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions";
    private static final String OPENAI_IMAGES_URL = "https://api.openai.com/v1/images/generations";
    private static final String CHAT_MODEL = "gpt-4o-mini";
    private static final String IMAGE_MODEL = "gpt-image-1";
    private static final int CORRECT_MAX_TOKENS = 300;
    private static final int SCENE_MAX_TOKENS = 400;

    private static final String IMAGE_STYLE_PREFIX =
            "A single hand-drawn sketchnote / graphic-recording style illustration, like a live "
            + "conference visual note-taker's drawing: black ink doodle line art with minimal, "
            + "sparse color accents, clean and expressive, simple iconic shapes grouped together "
            + "in one composition. No text, no letters, no words, no captions anywhere in the "
            + "image. Scene: ";

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

            Sua tarefa é imaginar uma única cena visual, no estilo de um "sketchnote" (registro visual
            gráfico feito à mão, como os que ilustradores fazem ao vivo em conferências): vários elementos
            simples desenhados numa mesma composição, não uma foto realista. Essa cena deve representar,
            juntos, pelo menos 3 ações/ideias/tópicos diferentes mencionados no trecho.

            Responda APENAS com um objeto JSON compacto, sem markdown, sem crases, sem texto antes ou depois,
            exatamente no seguinte formato:
            {"title": "...", "imagePrompt": "..."}

            Regras:
            - "title": uma legenda curta (no máximo 8 palavras) para a cena, em português.
            - "imagePrompt": uma descrição em INGLÊS da cena a ser desenhada, para um gerador de imagens.
              Descreva elementos concretos (pessoas, objetos, ações, símbolos) que juntos representem pelo
              menos 3 ações/ideias distintas do trecho, como se fossem vários pequenos desenhos de um
              sketchnote reunidos numa composição. Não descreva texto, letras ou legendas dentro da imagem.
            - Se o trecho realmente só tiver 1 ou 2 ideias distintas, descreva o máximo de elementos
              relevantes que fizer sentido (mínimo 2), sem inventar conteúdo que não foi dito.
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
     */
    public CorrectResponse correct(String text) {
        JsonNode node = callChat(CORRECTION_SYSTEM_PROMPT, text, CORRECT_MAX_TOKENS);
        JsonNode correctedNode = node.path("correctedText");
        String corrected = correctedNode.isTextual() && !correctedNode.asText().isBlank()
                ? correctedNode.asText()
                : text;
        return new CorrectResponse(corrected);
    }

    /**
     * Gera o título e a descrição visual de um card de storyboard a partir de uma cena
     * mais ampla: o texto já corrigido de vários trechos acumulados, com conteúdo
     * suficiente para identificar múltiplas ações/ideias distintas.
     */
    public SummarizeResponse summarizeScene(String correctedSceneText) {
        JsonNode node = callChat(SCENE_SYSTEM_PROMPT, correctedSceneText, SCENE_MAX_TOKENS);

        JsonNode titleNode = node.path("title");
        JsonNode imagePromptNode = node.path("imagePrompt");
        String title = titleNode.isTextual() ? titleNode.asText() : "";
        String imagePrompt = imagePromptNode.isTextual() ? imagePromptNode.asText() : correctedSceneText;

        return new SummarizeResponse(title, imagePrompt);
    }

    /**
     * Gera a ilustração (estilo sketchnote) de um card a partir da descrição visual
     * produzida por {@link #summarizeScene(String)}.
     *
     * @param imagePrompt descrição em inglês da cena a ser desenhada
     * @return a imagem gerada, em base64
     * @throws SummaryGenerationException se a chave de API não estiver configurada, se a chamada
     *                                     à API de imagens da OpenAI falhar, ou se a resposta
     *                                     não contiver uma imagem
     */
    public IllustrationResponse illustrate(String imagePrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new SummaryGenerationException(
                    "Chave da API OpenAI não configurada (defina a variável de ambiente OPENAI_API_KEY)");
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", IMAGE_MODEL);
        root.put("prompt", IMAGE_STYLE_PREFIX + imagePrompt);
        root.put("n", 1);
        root.put("size", "1024x1024");
        root.put("quality", "medium");
        // Não envia "response_format": gpt-image-1 não aceita esse parâmetro (sempre devolve
        // b64_json) — o formato de retorno é tratado abaixo, aceitando b64_json ou url (para
        // manter compatibilidade caso o modelo mude no futuro).

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new SummaryGenerationException("Falha interna ao preparar a requisição de imagem.", e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_IMAGES_URL))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + apiKey)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new SummaryGenerationException(
                    "Falha ao conectar com a API de imagens da OpenAI. Verifique a conexão de rede e tente novamente.",
                    e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.error("OpenAI Images API returned status {}: {}", response.statusCode(), response.body());
            String upstreamMessage = extractErrorMessage(response.body());
            String detail = upstreamMessage != null
                    ? upstreamMessage
                    : "verifique a chave de API e tente novamente";
            throw new SummaryGenerationException(
                    "A API de imagens da OpenAI retornou um erro (status " + response.statusCode() + "): " + detail);
        }

        try {
            JsonNode root2 = objectMapper.readTree(response.body());
            JsonNode dataArr = root2.path("data");
            if (dataArr.isArray() && dataArr.size() > 0) {
                JsonNode first = dataArr.get(0);

                JsonNode b64Node = first.path("b64_json");
                if (b64Node.isTextual() && !b64Node.asText().isBlank()) {
                    return new IllustrationResponse(b64Node.asText());
                }

                // Algumas contas/modelos devolvem uma URL temporária em vez de base64;
                // baixa a imagem e converte para manter o mesmo contrato com o frontend.
                JsonNode urlNode = first.path("url");
                if (urlNode.isTextual() && !urlNode.asText().isBlank()) {
                    return new IllustrationResponse(downloadAndEncode(urlNode.asText()));
                }
            }
            throw new SummaryGenerationException("Resposta inesperada da API de imagens da OpenAI (sem imagem).");
        } catch (SummaryGenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new SummaryGenerationException(
                    "Não foi possível interpretar a resposta da API de imagens da OpenAI.", e);
        }
    }

    private String downloadAndEncode(String imageUrl) {
        HttpRequest imageRequest = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        try {
            HttpResponse<byte[]> imageResponse = httpClient.send(imageRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (imageResponse.statusCode() < 200 || imageResponse.statusCode() >= 300) {
                throw new SummaryGenerationException(
                        "Falha ao baixar a imagem gerada (status " + imageResponse.statusCode() + ").");
            }
            return Base64.getEncoder().encodeToString(imageResponse.body());
        } catch (SummaryGenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new SummaryGenerationException("Falha ao baixar a imagem gerada pela OpenAI.", e);
        }
    }

    /**
     * Chama a API de Chat Completions da OpenAI com o prompt de sistema e o texto do
     * usuário informados, e devolve o corpo JSON já decodificado da resposta do modelo
     * (depois de remover eventuais blocos de código markdown ao redor).
     */
    private JsonNode callChat(String systemPrompt, String userText, int maxTokens) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new SummaryGenerationException(
                    "Chave da API OpenAI não configurada (defina a variável de ambiente OPENAI_API_KEY)");
        }

        String requestBody = buildChatRequestBody(systemPrompt, userText, maxTokens);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OPENAI_CHAT_URL))
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

    private String buildChatRequestBody(String systemPrompt, String userText, int maxTokens) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", CHAT_MODEL);
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
