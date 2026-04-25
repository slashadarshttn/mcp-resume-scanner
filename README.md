# mcp-resume-scanner

Spring Boot **MCP (Model Context Protocol) server** that exposes tools to:

- scan a local folder of PDF resumes
- rate resumes against job requirements using an OpenAI model (via Spring AI)
- export ratings to CSV

## What it provides (MCP tools)

The server registers the following MCP tools from `ResumeService`:

- **`scan_resumes(folderPath)`**
  - Scans a local folder for `*.pdf`, extracts text using PDFBox, and returns a human-readable summary.
- **`rate_resumes(requirements)`**
  - Rates all scanned resumes against a requirements string and returns a structured text block per file.
- **`export_csv(outputPath)`**
  - Exports the latest ratings to a CSV file at an absolute path.

Important behavior:

- **State is in-memory**: scanned text + ratings are stored in memory only.
- **Ordering matters**: call `scan_resumes` before `rate_resumes`, and `rate_resumes` before `export_csv`.
- **Re-scan resets**: calling `scan_resumes` clears prior scans/ratings.

## Requirements

- **Java**: 17
- **OpenAI API key**: set `OPENAI_API_KEY`
- **Input data**: a local folder containing PDF resumes

## Configuration

Config is in `src/main/resources/application.yml`.

- **Server**
  - `server.port`: `8083`
  - `server.address`: `0.0.0.0`
- **MCP server identity**
  - `spring.ai.mcp.server.name`: `resume-scanner-mcp-server`
  - `spring.ai.mcp.server.version`: `1.0.0`
- **OpenAI**
  - `spring.ai.openai.api-key`: `${OPENAI_API_KEY:}`
  - `spring.ai.openai.chat.options.model`: `gpt-5.4`

## Run locally

```bash
export OPENAI_API_KEY="..."
./gradlew bootRun
```

The HTTP server listens on `http://localhost:8083`.

## MCP HTTP transport endpoints (WebFlux SSE)

This project uses Spring AI’s WebFlux SSE transport. With default Spring AI settings, the endpoints are:

- **SSE endpoint**: `http://localhost:8083/sse`
- **Message endpoint**: `http://localhost:8083/mcp/message`

If you override these, see Spring AI properties:

- `spring.ai.mcp.server.sse-endpoint`
- `spring.ai.mcp.server.sse-message-endpoint`
- `spring.ai.mcp.server.base-url`

## Tests

```bash
./gradlew test
```
