package com.microsoft.stu;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.OutputBinding;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.CosmosDBOutput;
import com.microsoft.azure.functions.annotation.CosmosDBTrigger;

import com.azure.ai.openai.OpenAIAsyncClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.core.credential.KeyCredential;
import com.microsoft.semantickernel.Kernel;
import com.microsoft.semantickernel.SKBuilders;
import com.microsoft.semantickernel.connectors.ai.openai.util.AzureOpenAISettings;
import com.microsoft.semantickernel.exceptions.ConfigurationException;
import com.microsoft.semantickernel.orchestration.ContextVariables;
import com.microsoft.semantickernel.orchestration.SKContext;

import reactor.core.publisher.Mono;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * This class represents a function for processing Cosmos DB triggers and
 * generating summaries with Azure OpenAI and Semantic Kernel.
 */
public class Function {

    @FunctionName("cosmosDBProcessor")
    @SuppressWarnings("unused")
    public void cosmosDbProcessor(
            @CosmosDBTrigger(name = "items", databaseName = "transcription-db", collectionName = "transcriptions", createLeaseCollectionIfNotExists = true, connectionStringSetting = "CosmosDBConnectionString") String[] items,
            @CosmosDBOutput(name = "database", databaseName = "transcription-db", connectionStringSetting = "CosmosDBConnectionString", collectionName = "summmaries", createIfNotExists = true, partitionKey = "/id") OutputBinding<String> outputItem,
            final ExecutionContext context) {
        for (String item : items) {
            context.getLogger().info(item + " changed.");
            JSONObject json = new JSONObject(item);
            String summary = summarize(String.valueOf(json.get("transcription")), context);
            JSONObject summaryJson = new JSONObject(summary);
            summaryJson.put("id", json.get("id"));
            outputItem.setValue(summaryJson.toString());
        }
    }

    private String summarize(String item, ExecutionContext context) {

        context.getLogger().info("=> Run the Kernel");

        ContextVariables variables = SKBuilders.variables()
                .withInput(item)
                .build();

        Kernel kernel;
        try {
            kernel = kernel();
            Mono<SKContext> result = kernel.runAsync(
                    variables,
                    kernel.getSkill("Transcriptions").getFunction("Summarizer", null));

            context.getLogger().info("=> Result");
            String summary = result.block().getResult();
            context.getLogger().info(summary);
            return summary;
        } catch (ConfigurationException e) {
            context.getLogger().severe("=> Error: " + e.getMessage());
        }
        return "";
    }

    private Kernel kernel() throws ConfigurationException {

        OpenAIAsyncClient client = openAIAsyncClient();

        Kernel kernel = SKBuilders.kernel()
                .withDefaultAIService(SKBuilders.chatCompletion()
                        .withModelId(settings().getDeploymentName())
                        .withOpenAIClient(client)
                        .build())
                .build();

        kernel.importSkillFromResources("plugins", "Transcriptions", "Summarizer");

        return kernel;
    }

    private OpenAIAsyncClient openAIAsyncClient() throws ConfigurationException {
        // Create an Azure OpenAI client
        AzureOpenAISettings settings = settings();
        return new OpenAIClientBuilder().endpoint(settings.getEndpoint())
                .credential(new KeyCredential(settings.getKey())).buildAsyncClient();
    }

    private AzureOpenAISettings settings() throws ConfigurationException {

        Map<String, String> map = new HashMap<>();
        map.put(AzureOpenAISettings.getDefaultSettingsPrefix() + "."
                + AzureOpenAISettings.getKeySuffix(), getProperty("AzureOpenAIKey"));
        map.put(AzureOpenAISettings.getDefaultSettingsPrefix() + "."
                + AzureOpenAISettings.getAzureOpenAiEndpointSuffix(), getProperty("AzureOpenAIEndpoint"));
        map.put(AzureOpenAISettings.getDefaultSettingsPrefix() + "."
                + AzureOpenAISettings.getAzureOpenAiDeploymentNameSuffix(), getProperty("AzureOpenAIDeploymentName"));

        AzureOpenAISettings settings = new AzureOpenAISettings(map);
        return settings;
    }

    private String getProperty(String propertyName) {
        String propertyValue = System.getenv(propertyName);
        if (propertyValue == null) {
            throw new IllegalArgumentException("Missing property: " + propertyName);
        }
        return propertyValue;
    }

}
