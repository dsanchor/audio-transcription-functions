package com.microsoft.stu;

import com.microsoft.azure.functions.annotation.*;
import com.microsoft.cognitiveservices.speech.CancellationReason;
import com.microsoft.cognitiveservices.speech.ResultReason;
import com.microsoft.cognitiveservices.speech.SpeechConfig;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
import com.microsoft.cognitiveservices.speech.audio.AudioInputStream;
import com.microsoft.cognitiveservices.speech.audio.AudioStreamFormat;
import com.microsoft.cognitiveservices.speech.audio.PushAudioInputStream;
import com.microsoft.cognitiveservices.speech.transcription.ConversationTranscriber;

import java.util.concurrent.Semaphore;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import com.microsoft.azure.functions.*;

/**
 * This function will be invoked when a new or updated blob is detected at the
 * specified path. The blob contents are provided as input to this function.
 * Then, the function will transcribe the audio file and store the result in a Cosmos DB.
 */
public class BlobTriggerEventGrid {


    @FunctionName("BlobTriggerEventGrid")
    @StorageAccount("blobtriggertrans2024")
    public void run(
            @BlobTrigger(name = "content", path = "audios/{name}", dataType = "binary", source = "EventGrid") byte[] content,
            @BindingName("name") String name,
            @CosmosDBOutput(name = "database", databaseName = "transcription-db", connection = "CosmosDBConnectionString", containerName = "transcriptions", createIfNotExists = true, partitionKey = "/id") OutputBinding<String> outputItem,
            final ExecutionContext context) {
        context.getLogger().info("Java Blob trigger function processed a blob. Name: " + name + "\n  Size: "
                + content.length + " Bytes");

        try {
            // init speech config with subscription key and region
            SpeechConfig speechConfig = SpeechConfig.fromSubscription(getProperty("SPEECH_KEY"),
                    getProperty("SPEECH_REGION"));
            speechConfig.setSpeechRecognitionLanguage(getProperty("SPEECH_LANGUAGE"));

            // init audio input with the specified PCM format
            AudioStreamFormat audioFormat = AudioStreamFormat.getWaveFormatPCM(24000L, (short) 16, (short) 1);
            PushAudioInputStream pushStream = AudioInputStream.createPushStream(audioFormat);
            AudioConfig audioInput = AudioConfig.fromStreamInput(pushStream);

            // create semaphore to signal stop transcribing
            Semaphore stopRecognitionSemaphore = new Semaphore(0);

            // init conversation transcriber
            ConversationTranscriber conversationTranscriber = new ConversationTranscriber(speechConfig, audioInput);
            {
                // push content from blob to audio input stream
                pushStream.write(content);

                // init json output
                JSONObject jsonObject = initializeJsonObject(name);

                context.getLogger().info("\n Subscribing to events.");

                conversationTranscriber.transcribing.addEventListener((s, e) -> {
                    context.getLogger().fine("TRANSCRIBING: Text=" + e.getResult().getText());
                });

                conversationTranscriber.transcribed.addEventListener((s, e) -> {
                    if (e.getResult().getReason() == ResultReason.RecognizedSpeech) {
                        context.getLogger().info("TRANSCRIBED: Text=" + e.getResult().getText() + " Speaker ID="
                                + e.getResult().getSpeakerId());

                        Map<String, Object> map = new HashMap<>();
                        map.put("speakerId", e.getResult().getSpeakerId());
                        map.put("text", e.getResult().getText());
                        map.put("offset", e.getResult().getOffset());
                        map.put("duration", e.getResult().getDuration());
                        // add transcription messages to json object
                        jsonObject.append("transcription", map);
                    } else if (e.getResult().getReason() == ResultReason.NoMatch) {
                        context.getLogger().warning("NOMATCH: Speech could not be transcribed.");
                    }
                });

                conversationTranscriber.canceled.addEventListener((s, e) -> {
                    context.getLogger().info("CANCELED: Reason=" + e.getReason());

                    if (e.getReason() == CancellationReason.Error) {
                        context.getLogger().severe("CANCELED: ErrorCode=" + e.getErrorCode());
                        context.getLogger().severe("CANCELED: ErrorDetails=" + e.getErrorDetails());
                        context.getLogger().severe("CANCELED: Did you update the subscription info?");
                    }
                    // release semaphore to stop transcribing
                    stopRecognitionSemaphore.release();
                });

                conversationTranscriber.sessionStarted.addEventListener((s, e) -> {
                    context.getLogger().info("\n    Session started event.");
                });

                conversationTranscriber.sessionStopped.addEventListener((s, e) -> {
                    context.getLogger().info("\n    Session stopped event.");
                });

                // start transcribing
                conversationTranscriber.startTranscribingAsync().get();

                // close audio input stream
                pushStream.close();

                // and wait for stop signal
                stopRecognitionSemaphore.acquire();

                // serialize json object to string and set output binding (Cosmos DB)
                String jsonDocument = jsonObject.toString();
                context.getLogger().info("Document: " + jsonDocument);
                outputItem.setValue(jsonDocument);

                // stop transcribing
                context.getLogger().info("\n Stop transcribing.");
                conversationTranscriber.stopTranscribingAsync().get();
            }
            speechConfig.close();
            conversationTranscriber.close();
        } catch (Exception ex) {
            context.getLogger().severe("Unexpected exception: " + ex.getMessage());
        }
    }

    private String getProperty(String propertyName) {
        String propertyValue = System.getenv(propertyName);
        if (propertyValue == null) {
            throw new IllegalArgumentException("Missing property: " + propertyName);
        }
        return propertyValue;
    }

    private JSONObject initializeJsonObject(String name) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("id", String.valueOf(Math.abs(new Random().nextInt())));
        jsonObject.put("audioFile", name);
        return jsonObject;
    }
}
