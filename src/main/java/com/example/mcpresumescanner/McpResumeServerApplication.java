package com.example.mcpresumescanner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

@SpringBootApplication
public class McpResumeServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpResumeServerApplication.class, args);
    }

    @Bean
    public ToolCallbackProvider toolCallbackProvider(ResumeService resumeService) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(resumeService)
                .build();
    }
}
