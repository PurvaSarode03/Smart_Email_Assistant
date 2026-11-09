package com.email.writer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
public class EmailGeneratorService {

    private static final List<String> MODELS = List.of(
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b",
            "qwen/qwen3-32b"
    );

    private final WebClient webClient;
    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public EmailGeneratorService(WebClient.Builder webClientBuilder,
                                 @Value("${gemini.api.key}") String geminiApiKey) {
        this.apiKey = geminiApiKey.trim();
        this.webClient = webClientBuilder
                .baseUrl("https://api.groq.com/openai/v1/chat/completions")
                .build();
    }

    public String generateEmailReply(EmailRequest emailRequest) {
        String prompt = buildPrompt(emailRequest);
        StringBuilder errors = new StringBuilder();

        for (String model : MODELS) {
            try {
                String reply = callGroq(model, prompt);
                if (reply != null && !reply.isBlank()) {
                    System.out.println("USED MODEL: " + model);
                    return reply;
                }
                errors.append(model).append(": empty reply\n");
            } catch (Exception e) {
                System.out.println("MODEL FAILED [" + model + "]: " + e.getMessage());
                errors.append(model).append(": ").append(e.getMessage()).append("\n");
            }
        }
        throw new RuntimeException("All models failed:\n" + errors);
    }

    private String callGroq(String model, String prompt) throws Exception {
        Map<String, Object> requestBody = Map.of(
                "model", model,
                "temperature", 0.5,
                "messages", List.of(
                        Map.of("role", "system", "content",
                                "You are a professional email assistant. Write only the reply email body. "
                                        + "Reply directly to the points in the original email. "
                                        + "Do not invent facts, dates, names or commitments. "
                                        + "Do not include a subject line or explanations."),
                        Map.of("role", "user", "content", prompt)
                )
        );

        String response = webClient.post()
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(status -> status.isError(), res ->
                        res.bodyToMono(String.class)
                                .map(body -> new RuntimeException(body)))
                .bodyToMono(String.class)
                .block();

        JsonNode root = mapper.readTree(response);
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        return content.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    private String buildPrompt(EmailRequest emailRequest) {
        String tone = (emailRequest.getTone() != null && !emailRequest.getTone().isEmpty())
                ? emailRequest.getTone() : "professional";

        return "Write a reply to the email below.\n"
                + "Tone: " + tone + "\n"
                + "Keep it concise (under 150 words), address every question asked, "
                + "and end with a polite closing.\n\n"
                + "EMAIL:\n" + emailRequest.getEmailContent();
    }
}
