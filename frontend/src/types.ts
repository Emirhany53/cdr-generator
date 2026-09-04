export type BerTagClass = "UNIVERSAL" | "APPLICATION" | "CONTEXT" | "PRIVATE";

export interface AsnField {
  fieldName: string;
  fieldType: string;
  optional: boolean;
  repeated: boolean;
  tagNumber: number | null;
  tagClass: BerTagClass | null;
  explicit: boolean;
  /** Alanın tipi bir ASN.1 CHOICE ise true. `children` yalnızca backend'in
   * seçtiği TEK alternatifi taşır; tüm adlar `choiceAlternatives`'tadır. */
  choice: boolean;
  /** CHOICE tipinin adı (ör. "InvolvedParty"); CHOICE değilse null. */
  choiceTypeName: string | null;
  /** Alternatif adları, tanım sırasıyla (ör. ["sIP-URI", "tEL-URI"]). */
  choiceAlternatives: string[] | null;
  /** EMM'in IMPLICIT CHOICE'ları yanlış okuduğu modül ailesi. `choice`,
   * `!explicit` ve `optional` ile birlikte: referenceMode olmadan bu alan
   * değer girilse bile üretilmez. */
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
