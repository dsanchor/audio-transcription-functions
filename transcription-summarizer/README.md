# Azure Function Summarizer

This function will use a Cosmos DB trigger to process the text from the Speech to text function and summarize it using the Azure Open AI Service with Semantic Kernel.

Once the summary is generated, it will be stored back in a new Cosmos DB container.

## Setup

### Environment variables

The following environment variables are required:

```bash
export RESOURCE_GROUP=<your-resource-group>
export LOCATION=<your-location>
```	
### Steps

Deploy function app:

```bash
mvn clean package azure-functions:deploy -DfunctionAppName=transcription-summarizer-func-app -DresourceGroup=$RESOURCE_GROUP -Dlocation=$LOCATION
```

Create app settings for function app with Cosmos DB connection string:

```bash
export COSMOSDB_CONNECTION_STRING=$(az cosmosdb keys list --name transcription-cosmosdb --resource-group $RESOURCE_GROUP --type connection-strings --query connectionStrings[0].connectionString --output tsv)
az functionapp config appsettings set --name transcription-summarizer-func-app --resource-group $RESOURCE_GROUP --settings "CosmosDBConnectionString"="$COSMOSDB_CONNECTION_STRING"
```

Create app settings for Azure Open AI:

```bash
export AZURE_OPENAI_KEY=<your-key>
az functionapp config appsettings set --name transcription-summarizer-func-app --resource-group $RESOURCE_GROUP --settings "AzureOpenAIKey"="$AZURE_OPENAI_KEY"
export AZURE_OPENAI_ENDPOINT=<your-endpoint>
az functionapp config appsettings set --name transcription-summarizer-func-app --resource-group $RESOURCE_GROUP --settings "AzureOpenAIEndpoint"="$AZURE_OPENAI_ENDPOINT"
export AZURE_OPENAI_MODEL=<your-model>
az functionapp config appsettings set --name transcription-summarizer-func-app --resource-group $RESOURCE_GROUP --settings "AzureOpenAIDeploymentName"="$AZURE_OPENAI_MODEL"
```
