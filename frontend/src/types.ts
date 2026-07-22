export type BerTagClass = "UNIVERSAL" | "APPLICATION" | "CONTEXT" | "PRIVATE";

export interface AsnField {
  fieldName: string;
  fieldType: string;
  optional: boolean;
  repeated: boolean;
  tagNumber: number | null;
  tagClass: BerTagClass | null;
  explicit: boolean;
  children: AsnField[] | null;
}

export interface AsnStructure {
  structureName: string;
  fields: AsnField[];
  choiceRoot: boolean;
  choiceTypeName: string | null;
  choiceAlternatives: string[] | null;
}

export type OutputFormat = "ascii" | "ber";
export type StructureSourceMode = "existing" | "inline";

export interface ApiErrorBody {
  timestamp?: string;
  status?: number;
  error?: string;
  message?: string;
  path?: string;
}
