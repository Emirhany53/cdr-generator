export type BerTagClass = "UNIVERSAL" | "APPLICATION" | "CONTEXT" | "PRIVATE";

export interface AsnField {
  fieldName: string;
  fieldType: string;
  optional: boolean;
  repeated: boolean;
  tagNumber: number | null;
  tagClass: BerTagClass | null;
  explicit: boolean;
  /** True when this field's value is one alternative of an ASN.1 CHOICE
   * (e.g. sIP-URI vs tEL-URI). `children` holds only the ONE alternative
   * the backend resolved by default; `choiceAlternatives` lists every name
   * a picker can offer instead. */
  choice: boolean;
  /** For a CHOICE field, the CHOICE type's own name (e.g. "InvolvedParty").
   * Null when `choice` is false. */
  choiceTypeName: string | null;
  /** For a CHOICE field, its alternative names in declaration order (e.g.
   * ["sIP-URI", "tEL-URI"]). Null when `choice` is false. */
  choiceAlternatives: string[] | null;
  /** True when this field sits in the module family whose decoder mis-reads
   * an IMPLICIT-tagged OPTIONAL CHOICE - CdrRecordBuilder.shouldSkipImplicitChoice
   * silently omits such a field from generation unless referenceMode is true
   * and the caller describes it. Combined with `choice`, `!explicit` and
   * `optional`, this is the exact (backend) condition under which a value
   * the user enters would otherwise be silently dropped. */
  decoderHoistsImplicitChoice: boolean;
  children: AsnField[] | null;
}

export interface AsnStructure {
  structureName: string;
  fields: AsnField[];
  choiceRoot: boolean;
  choiceTypeName: string | null;
  choiceAlternatives: string[] | null;
}

export type StructureSourceMode = "existing" | "inline";

export interface ApiErrorBody {
  timestamp?: string;
  status?: number;
  error?: string;
  message?: string;
  path?: string;
}
