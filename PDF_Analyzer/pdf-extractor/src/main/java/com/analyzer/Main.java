package com.analyzer;

import java.util.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration; 
import java.util.concurrent.CompletableFuture;

import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.Loader;

// LangChain4j Core Modules
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding; 
import dev.langchain4j.model.embedding.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;

// Vector Database Modules
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingMatch;

// LLM Streaming Brain Module
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;

public class Main {
    public static void main(String args[]) {
        // 1. WORKSPACE DIRECTORY SETUP
        File dir = new File("documents");
        if (!dir.exists()) {
            dir.mkdir();
            System.out.println("Created workspace directory: " + dir.getAbsolutePath());
            System.out.println("Please drop your PDF documents into the 'documents' folder and restart the app.");
            return;
        }

        File[] pdfFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".pdf"));
        if (pdfFiles == null || pdfFiles.length == 0) {
            System.out.println("No documents found inside the 'documents' folder.");
            System.out.println("Drop your PDF files into: " + dir.getAbsolutePath() + " and restart.");
            return;
        }

        // 2. COMPUTE CACHE STATE SIGNATURE
        StringBuilder stateSignature = new StringBuilder();
        Arrays.sort(pdfFiles, Comparator.comparing(File::getName));
        for (File f : pdfFiles) {
            stateSignature.append(f.getName()).append(":").append(f.length()).append(";");
        }
        String currentSignature = stateSignature.toString();

        Path signaturePath = Path.of("multi_doc_signature.txt");
        Path vectorStorePath = Path.of("multi_doc_vectors.json");
        InMemoryEmbeddingStore<TextSegment> vectorDatabase;

        var embeddingModel = new AllMiniLmL6V2EmbeddingModel();

        // 3. SILENT CACHE LOADING / PROCESSING MATCH
        boolean isCacheValid = false;
        try {
            if (Files.exists(vectorStorePath) && Files.exists(signaturePath)) {
                String savedSignature = Files.readString(signaturePath);
                if (savedSignature.equals(currentSignature)) {
                    isCacheValid = true;
                }
            }
        } catch (IOException e) {
            // Silently fall back to parse if validation tracking errors out
        }

        if (isCacheValid) {
            vectorDatabase = InMemoryEmbeddingStore.fromFile(vectorStorePath);
        } else {
            vectorDatabase = new InMemoryEmbeddingStore<>();

            for (File file : pdfFiles) {
                try (PDDocument document = Loader.loadPDF(file)) {
                    StringBuilder structuredText = new StringBuilder();
                    
                    var firstPage = document.getPage(0);
                    float pageWidth = firstPage.getMediaBox().getWidth();
                    float pageHeight = firstPage.getMediaBox().getHeight();
                    
                    int bodyStartY = (int) (pageHeight * 0.25);
                    int bodyHeight = (int) (pageHeight * 0.50);
                    boolean multiColumnDetected = false;
                    
                    int stripWidth = 10;
                    PDFTextStripperByArea layoutTester = new PDFTextStripperByArea();
                    int startScanX = (int) (pageWidth * 0.20);
                    int endScanX = (int) (pageWidth * 0.80);
                    int zoneId = 0;
                    
                    for (int x = startScanX; x < endScanX; x += 15) {
                        java.awt.Rectangle testZone = new java.awt.Rectangle(x, bodyStartY, stripWidth, bodyHeight);
                        layoutTester.addRegion("zone_" + zoneId, testZone);
                        zoneId++;
                    }
                    layoutTester.extractRegions(firstPage);
                    
                    int emptyGapsFound = 0;
                    for (int i = 0; i < zoneId; i++) {
                        String zoneText = layoutTester.getTextForRegion("zone_" + i);
                        if (zoneText == null || zoneText.trim().length() < 3) {
                            emptyGapsFound++;
                        }
                    }
                    
                    if (emptyGapsFound >= 1) { 
                        multiColumnDetected = true;
                    }
                    
                    if (multiColumnDetected) {
                        for (int p = 0; p < document.getNumberOfPages(); p++) {
                            var page = document.getPage(p);
                            float w = page.getMediaBox().getWidth();
                            float h = page.getMediaBox().getHeight();
                            
                            PDFTextStripperByArea areaStripper = new PDFTextStripperByArea();
                            areaStripper.setSortByPosition(true);
                            
                            int headerH = (int) (h * 0.12); 
                            int columnH = (int) (h - headerH);
                            int splitX = (int) (w * 0.5);
                            
                            java.awt.Rectangle headerZone = new java.awt.Rectangle(0, 0, (int) w, headerH);
                            java.awt.Rectangle leftColumnZone = new java.awt.Rectangle(0, headerH, splitX, columnH);
                            java.awt.Rectangle rightColumnZone = new java.awt.Rectangle(splitX, headerH, splitX, columnH);
                            
                            areaStripper.addRegion("header_p" + p, headerZone);
                            areaStripper.addRegion("left_p" + p, leftColumnZone);
                            areaStripper.addRegion("right_p" + p, rightColumnZone);
                            
                            areaStripper.extractRegions(page);
                            
                            structuredText.append(areaStripper.getTextForRegion("header_p" + p)).append("\n");
                            structuredText.append(areaStripper.getTextForRegion("left_p" + p)).append("\n");
                            structuredText.append(areaStripper.getTextForRegion("right_p" + p)).append("\n");
                        }
                    } else {
                        PDFTextStripper linearStripper = new PDFTextStripper();
                        linearStripper.setSortByPosition(false);
                        structuredText.append(linearStripper.getText(document));
                    }
                    
                    Document langchaindoc = Document.from(structuredText.toString());
                    var splitter = DocumentSplitters.recursive(1200, 200); 
                    List<TextSegment> chunks = splitter.split(langchaindoc);
                    
                    for (TextSegment chunk : chunks) {
                        TextSegment chunkWithMetadata = TextSegment.from(
                            chunk.text(), 
                            dev.langchain4j.data.document.Metadata.from("source_file", file.getName())
                        );
                        Embedding embedding = embeddingModel.embed(chunkWithMetadata).content();
                        vectorDatabase.add(embedding, chunkWithMetadata);
                    }
                    
                } catch (IOException e) {
                    System.err.println("Error loading document file database profile parameters: " + e.getMessage());
                }
            }

            try {
                vectorDatabase.serializeToFile(vectorStorePath);
                Files.writeString(signaturePath, currentSignature);
            } catch (IOException e) {
                // Silently manage write state conditions
            }
        }

        // 4. INITIALIZE STREAMING CORE
        OllamaStreamingChatModel streamingChatModel = OllamaStreamingChatModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("llama3.2") 
                .temperature(0.0)
                .timeout(Duration.ofMinutes(5)) 
                .build();
                
        // 5. CLEAN BOT INTERFACE HEADER DISPLAY
        System.out.println("\n========================================================");
        System.out.println(" AI Document Assistant Core Loaded Online");
        System.out.println(" Ready for interactions across your file knowledge base.");
        System.out.println(" Note: Type 'END' on a new line to submit long inputs.");
        System.out.println(" Type 'exit' to close down execution channels.");
        System.out.println("========================================================");

        // 6. CHATBOT RUNTIME INTERACTION LOOP
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("\nYou: \n> ");
            
            StringBuilder inputBuilder = new StringBuilder();
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                
                if (line.trim().equalsIgnoreCase("exit")) {
                    System.out.println("\nShutting down conversation terminal. Goodbye!");
                    scanner.close();
                    System.exit(0); 
                }
                if (line.trim().equalsIgnoreCase("END")) {
                    break;
                }
                inputBuilder.append(line).append("\n");
            }
            
            String userQuery = inputBuilder.toString().trim();
            if (userQuery.isEmpty()) continue;
            
            Embedding queryVector = embeddingModel.embed(userQuery).content();
            List<EmbeddingMatch<TextSegment>> matches = vectorDatabase.findRelevant(queryVector, 15, 0.0);
            
            if (!matches.isEmpty()) {
                StringBuilder combinedContext = new StringBuilder();
                for (EmbeddingMatch<TextSegment> match : matches) {
                    String documentSource = match.embedded().metadata().getString("source_file");
                    combinedContext.append("[DOCUMENT SOURCE: ").append(documentSource).append("]\n")
                                   .append(match.embedded().text()).append("\n---\n");
                }
                
                String structuredPrompt = 
                    "You are an expert multi-document document review assistant.\n" +
                    "Analyze the cross-document text context provided below to answer the user's question.\n\n" +
                    "CRITICAL DIRECTION:\n" +
                    "1. Answer ONLY the specific question asked by the user.\n" +
                    "2. Do NOT volunteer extra information, lists, skills, or summaries that were not explicitly requested.\n" +
                    "3. Base your response strictly on the facts in the context provided. If details are requested, explicitly cite the relevant file name.\n\n" +
                    "--- START GLOBAL WORKSPACE CONTEXT ---\n" +
                    combinedContext.toString() +
                    "--- END GLOBAL WORKSPACE CONTEXT ---\n\n" +
                    "Question: " + userQuery + "\n" +
                    "Answer: ";
                
                System.out.print("\nAI:\n");

                CompletableFuture<Response<AiMessage>> lockWindow = new CompletableFuture<>();

                streamingChatModel.generate(structuredPrompt, new StreamingResponseHandler<AiMessage>() {
                    @Override
                    public void onNext(String token) {
                        System.out.print(token);
                        System.out.flush();
                    }

                    @Override
                    public void onComplete(Response<AiMessage> response) {
                        System.out.println(); 
                        lockWindow.complete(response);
                    }

                    @Override
                    public void onError(Throwable error) {
                        System.err.println("\n[Error encountered during stream]: " + error.getMessage());
                        lockWindow.completeExceptionally(error);
                    }
                });

                lockWindow.join(); 
            } else {
                System.out.println("\nAI:\nNo relevant text patterns matched inside the repository indexes.");
            }
        }
    }
}