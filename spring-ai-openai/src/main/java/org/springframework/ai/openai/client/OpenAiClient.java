/*
 * Copyright 2023-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.ai.openai.client;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import org.springframework.ai.client.AiClient;
import org.springframework.ai.client.AiResponse;
import org.springframework.ai.client.AiStreamClient;
import org.springframework.ai.client.Generation;
import org.springframework.ai.metadata.ChoiceMetadata;
import org.springframework.ai.metadata.RateLimit;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletion;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage;
import org.springframework.ai.openai.metadata.OpenAiGenerationMetadata;
import org.springframework.ai.openai.metadata.support.OpenAiResponseHeaderExtractor;
import org.springframework.ai.prompt.Prompt;
import org.springframework.ai.prompt.messages.Message;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;

/**
 * {@link AiClient} implementation for {@literal OpenAI} backed by {@link OpenAiApi}.
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 * @author Ueibin Kim
 * @author John Blum
 * @author Josh Long
 * @author Jemin Huh
 * @see org.springframework.ai.client.AiClient
 * @see org.springframework.ai.client.AiStreamClient
 * @see OpenAiApi
 */
public class OpenAiClient implements AiClient, AiStreamClient {

	private Double temperature = 0.7;

	private String model = "gpt-3.5-turbo";

	private final Logger logger = LoggerFactory.getLogger(getClass());

	private final OpenAiApi openAiApi;

	public OpenAiClient(OpenAiApi openAiApi) {
		Assert.notNull(openAiApi, "OpenAiApi must not be null");
		this.openAiApi = openAiApi;
	}

	public String getModel() {
		return this.model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public Double getTemperature() {
		return this.temperature;
	}

	public void setTemperature(Double temperature) {
		this.temperature = temperature;
	}

	@Override
	public AiResponse generate(Prompt prompt) {

		List<Message> messages = prompt.getMessages();

		List<ChatCompletionMessage> chatCompletionMessages = messages.stream()
			.map(m -> new ChatCompletionMessage(m.getContent(),
					ChatCompletionMessage.Role.valueOf(m.getMessageType().getValue())))
			.toList();

		ResponseEntity<ChatCompletion> completionEntity = this.openAiApi.chatCompletionEntity(
				new OpenAiApi.ChatCompletionRequest(chatCompletionMessages, this.model, this.temperature.floatValue()));

		var chatCompletion = completionEntity.getBody();
		if (chatCompletion == null) {
			logger.warn("No chat completion returned for request: {}", chatCompletionMessages);
			return new AiResponse(List.of());
		}

		RateLimit rateLimits = OpenAiResponseHeaderExtractor.extractAiResponseHeaders(completionEntity);

		List<Generation> generations = chatCompletion.choices().stream().map(choice -> {
			return new Generation(choice.message().content(), Map.of("role", choice.message().role().name()))
				.withChoiceMetadata(ChoiceMetadata.from(choice.finishReason().name(), null));
		}).toList();

		return new AiResponse(generations,
				OpenAiGenerationMetadata.from(completionEntity.getBody()).withRateLimit(rateLimits));
	}

	@Override
	public Flux<AiResponse> generateStream(Prompt prompt) {
		List<Message> messages = prompt.getMessages();

		List<ChatCompletionMessage> chatCompletionMessages = messages.stream()
			.map(m -> new ChatCompletionMessage(m.getContent(),
					ChatCompletionMessage.Role.valueOf(m.getMessageType().getValue())))
			.toList();

		Flux<OpenAiApi.ChatCompletionChunk> completionChunks = this.openAiApi
			.chatCompletionStream(new OpenAiApi.ChatCompletionRequest(chatCompletionMessages, this.model,
					this.temperature.floatValue(), true));

		// For chunked responses, only the first chunk contains the choice role.
		// The rest of the chunks with same ID share the same role.
		ConcurrentHashMap<String, String> roleMap = new ConcurrentHashMap<>();

		// An alternative implementation that returns Flux<Generation> instead of
		// Flux<AiResponse>.
		// Flux<Generation> generationFlux = completionChunks.map(chunk -> {
		// String chunkId = chunk.id();
		// return chunk.choices().stream()
		// .map(choice -> {
		// if (choice.delta().role() != null) {
		// roleMap.putIfAbsent(chunkId, choice.delta().role().name());
		// }
		// return new Generation(choice.delta().content(),
		// Map.of("role", roleMap.get(chunkId)));
		// })
		// .toList();
		// }).flatMapIterable(generations -> generations);
		// return generationFlux;

		return completionChunks.map(chunk -> {
			String chunkId = chunk.id();
			List<Generation> generations = chunk.choices().stream().map(choice -> {
				if (choice.delta().role() != null) {
					roleMap.putIfAbsent(chunkId, choice.delta().role().name());
				}
				var generation = new Generation(choice.delta().content(), Map.of("role", roleMap.get(chunkId)));
				if (choice.finishReason() != null) {
					generation = generation.withChoiceMetadata(ChoiceMetadata.from(choice.finishReason().name(), null));
				}
				return generation;
			}).toList();
			return new AiResponse(generations);
		});

	}

}
