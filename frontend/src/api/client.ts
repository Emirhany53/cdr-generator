import type { AsnStructure, ApiErrorBody } from "../types";

// Spring Boot's context-path (see application.yml: server.servlet.context-path).
// Override at build time with VITE_API_BASE_URL if the backend runs elsewhere.
const API_BASE_URL: string =
  (import.meta.env.VITE_API_BASE_URL as string | undefined) ??
  "http://localhost:8080/cdr-generator/api/cdr";


export class ApiError extends Error {
  status: number;
  constructor(message: string, status: number) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

async function readErrorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as ApiErrorBody;
    if (body?.message) return body.message;
  } catch {
    // response body wasn't JSON — fall through to the generic message below
  }
  return `İstek başarısız oldu (HTTP ${response.status})`;
}

async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
    ...init,
  });
  if (!response.ok) {
    throw new ApiError(await readErrorMessage(response), response.status);
  }
  return (await response.json()) as T;
}

export function getStructureNames(): Promise<string[]> {
  return requestJson<string[]>("/structures");
}

export function getStructureDetails(
  structureName: string,
  choiceSelections?: Record<string, string>,
): Promise<AsnStructure> {
  const query = choiceSelections
    ? "?" + new URLSearchParams(choiceSelections).toString()
    : "";
  return requestJson<AsnStructure>(
    `/structures/${encodeURIComponent(structureName)}${query}`,
  );
}

export function parseInlineStructure(
  contents: string,
  structureName?: string,
  choiceSelections?: Record<string, string>,
): Promise<AsnStructure> {
  return requestJson<AsnStructure>("/structures/parse-inline", {
    method: "POST",
    body: JSON.stringify({ contents, structureName, choiceSelections }),
  });
}

export interface DownloadedFile {
  blob: Blob;
  fileName: string;
}

function extractFileName(response: Response, fallback: string): string {
  const header = response.headers.get("Content-Disposition");
  const match = header?.match(/filename="?([^"]+)"?/);
  return match?.[1] ?? fallback;
}

async function requestFile(
  path: string,
  body: unknown,
  fallbackFileName: string,
): Promise<DownloadedFile> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    throw new ApiError(await readErrorMessage(response), response.status);
  }
  const blob = await response.blob();
  return { blob, fileName: extractFileName(response, fallbackFileName) };
}

export interface GenerateTextParams {
  structureName?: string;
  contents?: string;
  fieldValues: Record<string, string>;
  choiceSelections: Record<string, string>;
  recordCount: number;
  /** Same flag, same meaning and same derivation as on GenerateBerParams -
   * /generate accepts it too, so .txt and .ber describe the same record
   * instead of the text output silently dropping a repeated CHOICE. */
  referenceMode?: boolean;
}

export function generateText(params: GenerateTextParams): Promise<DownloadedFile> {
  return requestFile("/generate", params, `${params.structureName ?? "cdr"}.txt`);
}

export interface GenerateBerParams {
  structureName?: string;
  contents?: string;
  fieldValues: Record<string, string>;
  choiceSelections: Record<string, string>;
  recordCount: number;
  /** Reference-driven generation (backend-only feature, already supported by
   * /generate-ber): required for a repeated CHOICE field's per-instance
   * indexed fieldValues to take effect at all, and has a real side effect -
   * any OTHER optional field left blank stops being auto-generated. The UI
   * derives this from the field tree's own decoderHoistsImplicitChoice flag
   * (see App.tsx's referenceModeRequiredPaths), never as a user toggle. */
  referenceMode?: boolean;
}

export function generateBer(params: GenerateBerParams): Promise<DownloadedFile> {
  return requestFile(
    "/generate-ber",
    params,
    `${params.structureName ?? "cdr"}.ber`,
  );
}

export function triggerBrowserDownload(file: DownloadedFile, fileName?: string): void {
  const url = URL.createObjectURL(file.blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = fileName ?? file.fileName;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
