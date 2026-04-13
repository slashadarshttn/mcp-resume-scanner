package com.example.mcpresumescanner;

import com.opencsv.CSVWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ResumeService {

    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);

    private final ChatModel chatModel;
    private volatile ChatClient chatClient;

    /**
     * In-memory store: fileName -> extracted text
     */
    private final Map<String, String> scannedResumes = new ConcurrentHashMap<>();

    /**
     * In-memory store: fileName -> rating result from LLM (JSON string)
     */
    private final Map<String, String> ratedResumes = new ConcurrentHashMap<>();

    public ResumeService(@Lazy ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    private ChatClient getChatClient() {
        if (chatClient == null) {
            chatClient = ChatClient.builder(chatModel).build();
        }
        return chatClient;
    }

    // ==================== TOOL 1: SCAN RESUMES ====================

    @Tool(name = "scan_resumes",
            description = "Scans a local folder for PDF resume files, extracts text from each PDF, " +
                    "and returns a summary of all found resumes with candidate names. " +
                    "Use this tool first before rating resumes.")
    public String scanResumes(
            @ToolParam(description = "Absolute path to the folder containing PDF resume files, e.g. /Users/john/resumes") String folderPath) {

        log.info("Scanning resumes in folder: {}", folderPath);
        scannedResumes.clear();
        ratedResumes.clear();

        File folder = new File(folderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            return "Error: Folder not found or is not a directory: " + folderPath;
        }

        File[] pdfFiles = folder.listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));
        if (pdfFiles == null || pdfFiles.length == 0) {
            return "No PDF files found in: " + folderPath;
        }

        StringBuilder summary = new StringBuilder();
        summary.append("Found ").append(pdfFiles.length).append(" PDF resume(s):\n\n");

        int count = 0;
        for (File pdf : pdfFiles) {
            count++;
            try {
                String text = extractTextFromPdf(pdf);
                scannedResumes.put(pdf.getName(), text);

                // Extract a preview (first 200 chars)
                String preview = text.length() > 200 ? text.substring(0, 200) + "..." : text;
                summary.append(count).append(". ").append(pdf.getName())
                        .append(" (").append(formatFileSize(pdf.length())).append(")\n")
                        .append("   Preview: ").append(preview.replaceAll("\\s+", " ").trim())
                        .append("\n\n");
            } catch (Exception e) {
                log.error("Failed to extract text from: {}", pdf.getName(), e);
                summary.append(count).append(". ").append(pdf.getName())
                        .append(" - ERROR: Could not extract text: ").append(e.getMessage())
                        .append("\n\n");
            }
        }

        summary.append("Total scanned: ").append(scannedResumes.size()).append("/").append(pdfFiles.length);
        log.info("Scan complete. {} resumes extracted.", scannedResumes.size());
        return summary.toString();
    }

    // ==================== TOOL 2: RATE RESUMES ====================

    @Tool(name = "rate_resumes",
            description = "Rates all previously scanned resumes against job requirements using AI. " +
                    "Returns candidate name, rating out of 10, matched skills, missing skills, and comments. " +
                    "You must call scan_resumes first before using this tool.")
    public String rateResumes(
            @ToolParam(description = "Job requirements to evaluate resumes against, e.g. 'Java Spring Boot developer with 3+ years experience, AWS, Docker, Microservices, REST APIs'") String requirements) {

        log.info("Rating {} resumes against requirements: {}", scannedResumes.size(), requirements);

        if (scannedResumes.isEmpty()) {
            return "Error: No resumes scanned yet. Please call scan_resumes first with a folder path.";
        }

        StringBuilder results = new StringBuilder();
        results.append("Resume Rating Results\n");
        results.append("Requirements: ").append(requirements).append("\n");
        results.append("=" .repeat(60)).append("\n\n");

        int count = 0;
        for (Map.Entry<String, String> entry : scannedResumes.entrySet()) {
            count++;
            String fileName = entry.getKey();
            String resumeText = entry.getValue();

            log.info("Rating resume {}/{}: {}", count, scannedResumes.size(), fileName);

            try {
                String rating = rateResumeWithLlm(fileName, resumeText, requirements);
                ratedResumes.put(fileName, rating);
                results.append(count).append(". ").append(fileName).append("\n");
                results.append(rating).append("\n");
                results.append("-".repeat(40)).append("\n\n");
            } catch (Exception e) {
                log.error("Failed to rate resume: {}", fileName, e);
                results.append(count).append(". ").append(fileName)
                        .append("\n   ERROR: ").append(e.getMessage()).append("\n\n");
            }
        }

        return results.toString();
    }

    // ==================== TOOL 3: EXPORT CSV ====================

    @Tool(name = "export_csv",
            description = "Exports the resume rating results to a CSV file. " +
                    "The CSV contains columns: File Name, Candidate Name, Rating (out of 10), " +
                    "Matched Skills, Missing Skills, Comment. " +
                    "You must call rate_resumes first before using this tool.")
    public String exportCsv(
            @ToolParam(description = "Absolute path for the output CSV file, e.g. /Users/john/resume_ratings.csv") String outputPath) {

        log.info("Exporting {} rated resumes to CSV: {}", ratedResumes.size(), outputPath);

        if (ratedResumes.isEmpty()) {
            return "Error: No rated resumes found. Please call rate_resumes first.";
        }

        try {
            // Ensure parent directory exists
            Path parentDir = Paths.get(outputPath).getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }

            try (CSVWriter writer = new CSVWriter(new FileWriter(outputPath))) {
                // Header row
                writer.writeNext(new String[]{
                        "File Name", "Candidate Name", "Rating (out of 10)",
                        "Matched Skills", "Missing Skills", "Comment"
                });

                // Data rows - parse LLM output for each resume
                for (Map.Entry<String, String> entry : ratedResumes.entrySet()) {
                    String fileName = entry.getKey();
                    String ratingText = entry.getValue();

                    String[] row = parseLlmRatingToCsvRow(fileName, ratingText);
                    writer.writeNext(row);
                }
            }

            return "CSV exported successfully to: " + outputPath +
                    " (" + ratedResumes.size() + " resumes)";

        } catch (Exception e) {
            log.error("Failed to export CSV", e);
            return "Error exporting CSV: " + e.getMessage();
        }
    }

    // ==================== PRIVATE HELPERS ====================

    /**
     * Extracts text content from a PDF file using Apache PDFBox
     */
    private String extractTextFromPdf(File pdfFile) throws IOException {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /**
     * Uses OpenAI LLM to rate a single resume against job requirements
     */
    private String rateResumeWithLlm(String fileName, String resumeText, String requirements) {
        String systemPrompt = """
                You are an expert HR recruiter and resume screener.
                Analyze the given resume against the job requirements and provide a structured evaluation.

                Your response MUST follow this EXACT format (one line per field):
                Candidate Name: <full name from resume>
                Rating: <number from 1 to 10>/10
                Matched Skills: <comma-separated list of skills found in resume that match requirements>
                Missing Skills: <comma-separated list of required skills NOT found in resume>
                Comment: <brief 1-2 sentence overall assessment>

                Be fair and accurate. Only rate based on what's actually in the resume text.
                If the resume text is garbled or unreadable, give a low rating and mention it.
                """;

        String userMessage = String.format("""
                JOB REQUIREMENTS:
                %s

                RESUME FILE: %s

                RESUME CONTENT:
                %s
                """, requirements, fileName, truncateText(resumeText, 4000));

        Prompt prompt = new Prompt(
                List.of(new SystemMessage(systemPrompt), new UserMessage(userMessage)),
                OpenAiChatOptions.builder().temperature(0.1).build()
        );

        ChatResponse chatResponse = getChatClient().prompt(prompt).call().chatResponse();

        if (chatResponse != null && chatResponse.getResult() != null
                && chatResponse.getResult().getOutput() != null) {
            return chatResponse.getResult().getOutput().getText();
        }
        return "Candidate Name: Unknown\nRating: 0/10\nMatched Skills: None\nMissing Skills: All\nComment: Failed to analyze resume.";
    }

    /**
     * Parses the structured LLM rating output into CSV row columns
     */
    private String[] parseLlmRatingToCsvRow(String fileName, String ratingText) {
        String candidateName = extractField(ratingText, "Candidate Name");
        String rating = extractField(ratingText, "Rating");
        String matchedSkills = extractField(ratingText, "Matched Skills");
        String missingSkills = extractField(ratingText, "Missing Skills");
        String comment = extractField(ratingText, "Comment");

        return new String[]{fileName, candidateName, rating, matchedSkills, missingSkills, comment};
    }

    /**
     * Extracts a field value from structured text like "Field Name: value"
     */
    private String extractField(String text, String fieldName) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase().startsWith(fieldName.toLowerCase() + ":")) {
                return trimmed.substring(fieldName.length() + 1).trim();
            }
        }
        return "N/A";
    }

    /**
     * Truncates text to a maximum length to avoid exceeding LLM token limits
     */
    private String truncateText(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "\n... [truncated]";
    }

    /**
     * Formats file size in human-readable form
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char prefix = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), prefix);
    }
}
