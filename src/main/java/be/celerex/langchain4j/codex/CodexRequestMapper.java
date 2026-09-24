package be.celerex.langchain4j.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import java.util.ArrayList;
import java.util.List;

final class CodexRequestMapper {
	private static final System.Logger LOG = System.getLogger(CodexRequestMapper.class.getName());

	static String request(ChatRequest request, boolean stream) {
		rejectUnsupported(request);
		ObjectNode root = CodexCredentials.JSON.createObjectNode();
		root.put("model", request.modelName() == null ? "gpt-5-codex" : request.modelName());
		root.put("store", false);
		root.put("stream", stream);
		root.put("parallel_tool_calls", true);
		root.put(
			"tool_choice",
			request.toolChoice() == null
				? "auto"
				: request.toolChoice().name().toLowerCase(java.util.Locale.ROOT)
		);
		root.putObject("reasoning").put("effort", "medium").put("summary", "auto");
		root.putArray("include").add("reasoning.encrypted_content");
		ArrayNode input = root.putArray("input");
		StringBuilder instructions = new StringBuilder();
		for (ChatMessage message : request.messages()) {
			if (message instanceof SystemMessage system) {
				if (!instructions.isEmpty()) {
					instructions.append("\n\n");
				}
				instructions.append(system.text());
			}
			else if (message instanceof UserMessage user) {
				addUserMessage(input, user);
			}
			else if (message instanceof AiMessage assistant) {
				if (assistant.images() != null && !assistant.images().isEmpty()) {
					throw new IllegalArgumentException("Codex does not support assistant image history");
				}
				if (assistant.text() != null && !assistant.text().isBlank()) {
					addMessage(input, "assistant", "output_text", assistant.text());
				}
				for (ToolExecutionRequest call : assistant.toolExecutionRequests()) {
					ObjectNode item = input.addObject();
					item.put("type", "function_call");
					item.put("call_id", call.id());
					item.put("name", call.name());
					item.put("arguments", call.arguments());
				}
			}
			else if (message instanceof ToolExecutionResultMessage result) {
				ObjectNode item = input.addObject();
				item.put("type", "function_call_output");
				item.put("call_id", result.id());
				item.put("output", result.text());
			}
			else {
				throw new IllegalArgumentException("Unsupported Codex message type: " + message.type());
			}
		}
		if (!instructions.isEmpty()) {
			root.put("instructions", instructions.toString());
		}
		if (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty()) {
			ArrayNode tools = root.putArray("tools");
			for (ToolSpecification specification : request.toolSpecifications()) addTool(tools, specification);
		}
		try {
			return CodexCredentials.JSON.writeValueAsString(root);
		}
		catch (Exception exception) {
			throw new IllegalStateException("Unable to serialize Codex request", exception);
		}
	}

	private static void addUserMessage(ArrayNode input, UserMessage user) {
		if (user.contents() == null || user.contents().isEmpty()) {
			throw new IllegalArgumentException("Codex user messages must contain text or images");
		}
		ObjectNode item = input.addObject();
		item.put("type", "message");
		item.put("role", "user");
		ArrayNode content = item.putArray("content");
		boolean hasContent = false;
		for (Content value : user.contents()) {
			if (value instanceof TextContent text) {
				if (text.text() != null && !text.text().isBlank()) {
					hasContent = true;
				}
				content.addObject().put("type", "input_text").put("text", text.text());
			}
			else if (value instanceof ImageContent image) {
				hasContent = true;
				addImage(content, image);
			}
			else if (value instanceof AudioContent || value instanceof PdfFileContent || value instanceof VideoContent) {
				throw new IllegalArgumentException("Unsupported Codex user content type: " + value.type());
			}
			else {
				throw new IllegalArgumentException("Unknown Codex user content type: " + value.getClass().getName());
			}
		}
		if (!hasContent) {
			throw new IllegalArgumentException("Codex user messages must not be empty");
		}
	}

	private static void addImage(ArrayNode content, ImageContent image) {
		ObjectNode entry = content.addObject().put("type", "input_image");
		if (image.image().url() != null) {
			entry.put("image_url", image.image().url().toString());
		}
		else if (image.image().base64Data() != null) {
			String mimeType = image.image().mimeType();
			if (mimeType == null || mimeType.isBlank()) {
				throw new IllegalArgumentException("Codex base64 images require a MIME type");
			}
			entry.put("image_url", "data:" + mimeType + ";base64," + image.image().base64Data());
		}
		else {
			throw new IllegalArgumentException("Codex images require a URL or base64 data");
		}
		if (image.detailLevel() == ImageContent.DetailLevel.ULTRA_HIGH) {
			throw new IllegalArgumentException("Codex does not support ULTRA_HIGH image detail");
		}
		if (image.detailLevel() != null) {
			entry.put("detail", image.detailLevel().name().toLowerCase(java.util.Locale.ROOT));
		}
	}

	private static void addMessage(ArrayNode input, String role, String text) {
		addMessage(input, role, "input_text", text);
	}

	private static void addMessage(ArrayNode input, String role, String contentType, String text) {
		ObjectNode item = input.addObject();
		item.put("type", "message");
		item.put("role", role);
		item.putArray("content").addObject().put("type", contentType).put("text", text);
	}

	private static void addTool(ArrayNode tools, ToolSpecification specification) {
		try {
			JsonNode source = CodexCredentials.JSON.readTree(specification.toJson());
			JsonNode parameters = source.path("parameters");
			if (!parameters.isObject()) {
				throw new IllegalArgumentException("Tool " + specification.name() + " has no JSON Schema parameters");
			}
			ObjectNode tool = tools.addObject();
			tool.put("type", "function");
			tool.put("name", specification.name());
			if (specification.description() != null) {
				tool.put("description", specification.description());
			}
			tool.set("parameters", parameters);
			tool.put("strict", false);
		}
		catch (Exception exception) {
			throw new IllegalArgumentException("Unable to serialize tool specification " + specification.name(), exception);
		}
	}

	private static void rejectUnsupported(ChatRequest request) {
		if (request.temperature() != null
				|| request.topP() != null
				|| request.topK() != null
				|| request.frequencyPenalty() != null
				|| request.presencePenalty() != null
				|| request.maxOutputTokens() != null
				|| (request.stopSequences() != null && !request.stopSequences().isEmpty())
				|| (request.responseFormat() != null
						&& request.responseFormat() != dev.langchain4j.model.chat.request.ResponseFormat.TEXT)) {
			throw new IllegalArgumentException(
				"Codex backend does not "
					+ "support sampling, token limits, stop sequences, or responseFormat through this "
					+ "adapter"
			);
		}
	}

	static String errorMessage(int statusCode, String body) {
		String detail = null;
		try {
			JsonNode root = CodexCredentials.JSON.readTree(body);
			JsonNode error = root.path("error");
			String code = error.path("code").asText(null);
			String message = error.path("message").asText(null);
			if (message != null && !message.isBlank()) {
				detail = code == null || code.isBlank() ? message : code + ": " + message;
			}
		}
		catch (Exception exception) {
			LOG.log(System.Logger.Level.DEBUG, "Could not parse Codex error response as JSON", exception);
		}
		if (detail == null && body != null && !body.isBlank()) {
			detail = body.replaceAll("[\\r\\n\\t]+", " ").replaceAll("[^\\x20-\\x7E]", "?").trim();
		}
		if (detail != null && detail.length() > 500) {
			detail = detail.substring(0, 500);
		}
		return "Codex request failed with HTTP " + statusCode
			+ (detail == null
					|| detail.isBlank() ? "" : ": " + detail);
	}

	static ChatResponse response(String body) {
		try {
			JsonNode root = CodexCredentials.JSON.readTree(body);
			StringBuilder text = new StringBuilder();
			List<ToolExecutionRequest> calls = new ArrayList<>();
			for (JsonNode item : root.path("output")) {
				if ("message".equals(item.path("type").asText())) {
					for (JsonNode content : item.path("content")) {
						if ("output_text".equals(content.path("type").asText()) || content.has("text")) {
							text.append(content.path("text").asText());
						}
					}
				}
				else if ("function_call".equals(item.path("type").asText())) {
					calls.add(
						ToolExecutionRequest.builder()
							.id(item.path("call_id").asText())
							.name(item.path("name").asText())
							.arguments(item.path("arguments").asText())
							.build()
					);
				}
			}
			JsonNode usage = root.path("usage");
			JsonNode inputDetails = usage.path("input_tokens_details");
			JsonNode outputDetails = usage.path("output_tokens_details");
			String thinking = reasoning(root.path("output"));
			AiMessage message = AiMessage.builder()
				.text(text.toString())
				.thinking(thinking.isBlank() ? null : thinking)
				.toolExecutionRequests(calls)
				.build();
			return ChatResponse.builder()
				.id(root.path("id").asText(null))
				.modelName(root.path("model").asText(null))
				.aiMessage(message)
				.finishReason(finishReason(root, calls))
				.tokenUsage(
					new CodexTokenUsage(
						integerOrNull(usage, "input_tokens"),
						integerOrNull(usage, "output_tokens"),
						integerOrNull(usage, "total_tokens"),
						integerOrNull(inputDetails, "cached_tokens"),
						integerOrNull(inputDetails, "cache_write_tokens"),
						integerOrNull(outputDetails, "reasoning_tokens")
					)
				)
				.build();
		}
		catch (Exception exception) {
			throw new IllegalArgumentException("Invalid Codex response", exception);
		}
	}

	private static String reasoning(JsonNode output) {
		StringBuilder result = new StringBuilder();
		for (JsonNode item : output) {
			if (!"reasoning".equals(item.path("type").asText())) {
				continue;
			}
			for (JsonNode summary : item.path("summary")) {
				result.append(summary.path("text").asText());
			}
			for (JsonNode content : item.path("content")) {
				result.append(content.path("text").asText());
			}
		}
		return result.toString();
	}

	private static FinishReason finishReason(JsonNode root, List<ToolExecutionRequest> calls) {
		if (!calls.isEmpty()) {
			return FinishReason.TOOL_EXECUTION;
		}
		String status = root.path("status").asText();
		String reason = root.path("incomplete_details").path("reason").asText();
		if ("incomplete".equals(status) && ("max_output_tokens".equals(reason) || "max_tokens".equals(reason))) {
			return FinishReason.LENGTH;
		}
		if ("content_filter".equals(reason)) {
			return FinishReason.CONTENT_FILTER;
		}
		return "completed".equals(status) || status.isBlank() ? FinishReason.STOP : FinishReason.OTHER;
	}

	private static Integer integerOrNull(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (!value.isNumber()) {
			return null;
		}
		long result = value.longValue();
		if (result < 0 || result > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Codex token count is outside LangChain4j's supported integer range: " + field);
		}
		return (int) result;
	}
}
