import type { AsnField, BerTagClass } from "../types";

interface FieldFormProps {
  fields: AsnField[];
  pathPrefix: string;
  values: Record<string, string>;
  onValueChange: (path: string, value: string) => void;
  repeatCounts: Record<string, number>;
  onRepeatCountChange: (path: string, count: number) => void;
  /** 0 for the very first call (from App.tsx). A depth-0 group/repeated field
   * (e.g. the single CHOICE alternative wrapper like "refillRecordV2") is
   * rendered already expanded, with no click needed — there's nothing to
   * hide it FROM at that level. Only fields nested one level deeper or more
   * start collapsed. Leaves are always click-to-expand regardless of depth. */
  depth?: number;
}

/** Recursively renders an AsnField tree as a value-entry form. Every leaf's
 * input is keyed by its dotted/indexed path (e.g. "adjustmentRecordV2.hostName",
 * "items[0].volume") — the exact convention CdrRecordBuilder.lookupUserValue
 * expects on the backend. Fields left blank are simply omitted from the
 * fieldValues map that gets sent, so the backend auto-generates them. */
export default function FieldForm({
  fields, pathPrefix, values, onValueChange, repeatCounts, onRepeatCountChange, depth = 0,
}: FieldFormProps) {
  return (
    <div className="field-form">
      {fields.map((field) => {
        const path = pathPrefix ? `${pathPrefix}.${field.fieldName}` : field.fieldName;
        return (
          <FieldEntry
            key={path}
            field={field}
            path={path}
            depth={depth}
            values={values}
            onValueChange={onValueChange}
            repeatCounts={repeatCounts}
            onRepeatCountChange={onRepeatCountChange}
          />
        );
      })}
    </div>
  );
}

function FieldEntry({
  field, path, depth, values, onValueChange, repeatCounts, onRepeatCountChange,
}: {
  field: AsnField;
  path: string;
  depth: number;
} & Pick<FieldFormProps, "values" | "onValueChange" | "repeatCounts" | "onRepeatCountChange">) {
  if (field.repeated) {
    const count = repeatCounts[path] ?? 1;
    const body = (
      <div className="repeated-field-body">
        <div className="repeated-controls">
          <button
            type="button"
            className="btn btn-small"
            onClick={(e) => { e.preventDefault(); onRepeatCountChange(path, Math.max(0, count - 1)); }}
          >
            −
          </button>
          <span>{count} adet</span>
          <button
            type="button"
            className="btn btn-small"
            onClick={(e) => { e.preventDefault(); onRepeatCountChange(path, count + 1); }}
          >
            +
          </button>
        </div>
        {Array.from({ length: count }).map((_, idx) => {
          const itemPath = `${path}[${idx}]`;
          return (
            <div className="repeated-item" key={itemPath}>
              <div className="repeated-item-label">#{idx + 1}</div>
              {field.children && field.children.length > 0 ? (
                <FieldForm
                  fields={field.children}
                  pathPrefix={itemPath}
                  depth={depth + 1}
                  values={values}
                  onValueChange={onValueChange}
                  repeatCounts={repeatCounts}
                  onRepeatCountChange={onRepeatCountChange}
                />
              ) : (
                <LeafInput field={field} path={itemPath} value={values[itemPath] ?? ""} onChange={onValueChange} />
              )}
            </div>
          );
        })}
      </div>
    );
    const summaryContent = (
      <>
        <strong>{field.fieldName}</strong> <TagBadge field={field} />
        <span className="hint-inline">tekrarlı alan, {count} adet</span>
      </>
    );
    return depth === 0 ? (
      <div className="field-group field-group-top">
        <div className="field-group-top-header">{summaryContent}</div>
        {body}
      </div>
    ) : (
      <details className="repeated-field">
        <summary>{summaryContent}</summary>
        {body}
      </details>
    );
  }

  if (field.children && field.children.length > 0) {
    const body = (
      <div className="field-group-body">
        <FieldForm
          fields={field.children}
          pathPrefix={path}
          depth={depth + 1}
          values={values}
          onValueChange={onValueChange}
          repeatCounts={repeatCounts}
          onRepeatCountChange={onRepeatCountChange}
        />
      </div>
    );
    const summaryContent = (
      <>
        <strong>{field.fieldName}</strong> <TagBadge field={field} />
        {field.optional && <span className="badge badge-optional">opsiyonel</span>}
      </>
    );
    // Depth 0: this is the only (or one of very few) top-level containers —
    // e.g. the selected CHOICE alternative. Nothing to hide it from, so skip
    // the extra click and show its fields right away.
    return depth === 0 ? (
      <div className="field-group field-group-top">
        <div className="field-group-top-header">{summaryContent}</div>
        {body}
      </div>
    ) : (
      <details className="field-group">
        <summary>{summaryContent}</summary>
        {body}
      </details>
    );
  }

  return <LeafInput field={field} path={path} value={values[path] ?? ""} onChange={onValueChange} />;
}

function TagBadge({ field }: { field: AsnField }) {
  if (field.tagNumber === null || field.tagNumber === undefined) return null;
  const cls: BerTagClass = field.tagClass ?? "CONTEXT";
  return (
    <span className="tag-badge" title={`${cls} tag, ${field.explicit ? "EXPLICIT" : "IMPLICIT"}`}>
      [{cls === "CONTEXT" ? "" : cls + " "}{field.tagNumber}]
    </span>
  );
}

type LeafKind = "boolean" | "enumerated" | "integer" | "octet" | "text";

function classifyType(fieldType: string): LeafKind {
  const t = fieldType.toUpperCase();
  if (t.includes("BOOLEAN")) return "boolean";
  if (t.includes("ENUMERATED")) return "enumerated";
  if (t.includes("INTEGER")) return "integer";
  if (t.includes("OCTET STRING") || t.includes("TBCD")) return "octet";
  return "text";
}

function LeafInput({
  field, path, value, onChange,
}: {
  field: AsnField;
  path: string;
  value: string;
  onChange: (path: string, value: string) => void;
}) {
  const kind = classifyType(field.fieldType);

  return (
    <details className="leaf-field">
      <summary>
        {field.fieldName} <TagBadge field={field} />
        <span className="field-type">{field.fieldType}</span>
        {field.optional && <span className="badge badge-optional">opsiyonel</span>}
        {value !== "" && <span className="badge badge-filled">{value}</span>}
      </summary>
      <div className="leaf-field-body">
        {kind === "boolean" ? (
          <select value={value} onChange={(e) => onChange(path, e.target.value)}>
            <option value="">— otomatik üret —</option>
            <option value="1">true</option>
            <option value="0">false</option>
          </select>
        ) : kind === "integer" || kind === "enumerated" ? (
          <input
            type="number"
            placeholder={kind === "enumerated" ? "sayısal enum değeri (ör. 0)" : "boş = otomatik üret"}
            value={value}
            onChange={(e) => onChange(path, e.target.value)}
          />
        ) : kind === "octet" ? (
          <input
            type="text"
            className="mono"
            placeholder="hex (ör. 1A2B3C4D), boş = otomatik üret"
            value={value}
            onChange={(e) => onChange(path, e.target.value)}
          />
        ) : (
          <input
            type="text"
            placeholder="boş = otomatik üret"
            value={value}
            onChange={(e) => onChange(path, e.target.value)}
          />
        )}
      </div>
    </details>
  );
}
