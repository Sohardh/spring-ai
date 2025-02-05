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

package org.springframework.ai.bedrock.cohere;

import java.util.List;

import reactor.core.publisher.Flux;

import org.springframework.ai.bedrock.BedrockUsage;
import org.springframework.ai.bedrock.MessageToPromptConverter;
import org.springframework.ai.bedrock.cohere.api.CohereChatBedrockApi;
import org.springframework.ai.bedrock.cohere.api.CohereChatBedrockApi.CohereChatRequest;
import org.springframework.ai.bedrock.cohere.api.CohereChatBedrockApi.CohereChatResponse;
import org.springframework.ai.bedrock.cohere.api.CohereChatBedrockApi.CohereChatRequest.LogitBias;
import org.springframework.ai.bedrock.cohere.api.CohereChatBedrockApi.CohereChatRequest.ReturnLikelihoods;
import org.springframework.ai.bedrock.cohere.api.CohereChatBedrockApi.CohereChatRequest.Truncate;
import org.springframework.ai.client.AiClient;
import org.springframework.ai.client.AiResponse;
import org.springframework.ai.client.AiStreamClient;
import org.springframework.ai.client.Generation;
import org.springframework.ai.metadata.ChoiceMetadata;
import org.springframework.ai.metadata.Usage;
import org.springframework.ai.prompt.Prompt;

/**
 * @author Christian Tzolov
 * @since 0.8.0
 */
public class BedrockCohereChatClient implements AiClient, AiStreamClient {

	private final CohereChatBedrockApi chatApi;

	private Float temperature;

	private Float topP;

	private Integer topK;

	private Integer maxTokens;

	private List<String> stopSequences;

	private ReturnLikelihoods returnLikelihoods;

	private Integer numGenerations;

	private LogitBias logitBias;

	private Truncate truncate;

	public BedrockCohereChatClient(CohereChatBedrockApi chatApi) {
		this.chatApi = chatApi;
	}

	public BedrockCohereChatClient withTemperature(Float temperature) {
		this.temperature = temperature;
		return this;
	}

	public BedrockCohereChatClient withTopP(Float topP) {
		this.topP = topP;
		return this;
	}

	public BedrockCohereChatClient withTopK(Integer topK) {
		this.topK = topK;
		return this;
	}

	public BedrockCohereChatClient withMaxTokens(Integer maxTokens) {
		this.maxTokens = maxTokens;
		return this;
	}

	public BedrockCohereChatClient withStopSequences(List<String> stopSequences) {
		this.stopSequences = stopSequences;
		return this;
	}

	public BedrockCohereChatClient withReturnLikelihoods(ReturnLikelihoods returnLikelihoods) {
		this.returnLikelihoods = returnLikelihoods;
		return this;
	}

	public BedrockCohereChatClient withNumGenerations(Integer numGenerations) {
		this.numGenerations = numGenerations;
		return this;
	}

	public BedrockCohereChatClient withLogitBias(LogitBias logitBias) {
		this.logitBias = logitBias;
		return this;
	}

	public BedrockCohereChatClient withTruncate(Truncate truncate) {
		this.truncate = truncate;
		return this;
	}

	@Override
	public AiResponse generate(Prompt prompt) {
		CohereChatResponse response = this.chatApi.chatCompletion(this.createRequest(prompt, false));
		List<Generation> generations = response.generations().stream().map(g -> {
			return new Generation(g.text());
		}).toList();

		return new AiResponse(generations);
	}

	@Override
	public Flux<AiResponse> generateStream(Prompt prompt) {
		return this.chatApi.chatCompletionStream(this.createRequest(prompt, true)).map(g -> {
			if (g.isFinished()) {
				String finishReason = g.finishReason().name();
				Usage usage = BedrockUsage.from(g.amazonBedrockInvocationMetrics());
				return new AiResponse(
						List.of(new Generation("").withChoiceMetadata(ChoiceMetadata.from(finishReason, usage))));
			}
			return new AiResponse(List.of(new Generation(g.text())));
		});
	}

	private CohereChatRequest createRequest(Prompt prompt, boolean stream) {
		final String promptValue = MessageToPromptConverter.create().toPrompt(prompt.getMessages());

		return CohereChatRequest.builder(promptValue)
			.withTemperature(this.temperature)
			.withTopP(this.topP)
			.withTopK(this.topK)
			.withMaxTokens(this.maxTokens)
			.withStopSequences(this.stopSequences)
			.withReturnLikelihoods(this.returnLikelihoods)
			.withStream(stream)
			.withNumGenerations(this.numGenerations)
			.withLogitBias(this.logitBias)
			.withTruncate(this.truncate)
			.build();
	}

}
