import type { AsnField, BerTagClass } from "../types";

interface FieldFormProps {
  fields: AsnField[];
  pathPrefix: string;
  values: Record<string, string>;
  onValueChange: (path: string, value: string) => void;
  repeatCounts: Record<string, number>;
  onRepeatCountChange: (path: string, count: number) => void;
  /** Tekrarlı CHOICE'ta her örneğin seçili alternatifi; anahtar örneğin yolu
   * (ör. "list-Of-Calling-Party-Address[1]"). Kaydı olmayan örnek backend'in
   * varsayılanını kullanır. Yalnızca `repeated && choice` alanlarda okunur. */
  repeatedChoiceAlt: Record<string, string>;
  onRepeatedChoiceAltChange: (itemPath: string, alternativeName: string) => void;
  /** Tekrarsız (skaler) bir CHOICE alanının alternatifini değiştirir; App.tsx
   * yapıyı yeniden çeker ve alanın girdileri yeni alternatife göre kurulur. */
  onScalarChoiceChange: (path: string, alternativeName: string) => void;
  /** Skaler CHOICE için yeniden çekme sürerken true; seçicileri kilitler. */
  choiceUpdating: boolean;
  /** İlk çağrıda 0. Derinlik 0'daki grup/tekrarlı alan (kökteki tek CHOICE
   * alternatifi gibi) açık gelir; daha derindekiler kapalı başlar. */
  depth?: number;
}

/** AsnField ağacını değer giriş formu olarak çizer. Her yaprağın anahtarı
 * noktalı/indeksli yoludur (ör. "items[0].volume") — backend'in beklediği
 * biçim. Boş bırakılan alanlar istekte yer almaz, backend onları üretir. */
export default function FieldForm({
  fields, pathPrefix, values, onValueChange, repeatCounts, onRepeatCountChange,
  repeatedChoiceAlt, onRepeatedChoiceAltChange, onScalarChoiceChange, choiceUpdating, depth = 0,
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
            repeatedChoiceAlt={repeatedChoiceAlt}
            onRepeatedChoiceAltChange={onRepeatedChoiceAltChange}
            onScalarChoiceChange={onScalarChoiceChange}
            choiceUpdating={choiceUpdating}
          />
        );
      })}
    </div>
  );
}

function FieldEntry({
  field, path, depth, values, onValueChange, repeatCounts, onRepeatCountChange,
  repeatedChoiceAlt, onRepeatedChoiceAltChange, onScalarChoiceChange, choiceUpdating,
}: {
  field: AsnField;
  path: string;
  depth: number;
} & Pick<FieldFormProps, "values" | "onValueChange" | "repeatCounts" | "onRepeatCountChange"
  | "repeatedChoiceAlt" | "onRepeatedChoiceAltChange" | "onScalarChoiceChange" | "choiceUpdating">) {
  if (field.repeated) {
    const count = repeatCounts[path] ?? 1;
    // Örnek başına alternatif seçici yalnızca tekrarlı CHOICE'a ait. Sıradan
    // tekrarlı alanlar (SEQUENCE OF SEQUENCE) eski görünümünü korusun diye
    // koşul field.repeated'a değil, field.choice'a bağlı.
    const isRepeatedChoice = field.choice && !!field.choiceAlternatives?.length;
    const resolvedDefault = field.children?.[0] ?? null;
    // Boş bırakılırsa alan kayda hiç yazılmaz mı? Backend'in
    // shouldSkipImplicitChoice koşulunun aynısı, aynı bayraklardan okunuyor.
    const omittedWhenEmpty =
      field.choice && !field.explicit && field.optional && field.decoderHoistsImplicitChoice;
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
        {isRepeatedChoice && (
          <p className="hint">
            Bu, tekrarlı bir <strong>CHOICE</strong> alanı ({field.choiceTypeName}) — her örnek
            kendi alternatifini (ör. {field.choiceAlternatives?.join(" / ")}) bağımsız olarak taşıyabilir.
          </p>
        )}
        {count === 0 && (
          <p className="hint">
            Örnek eklenmedi
            {omittedWhenEmpty
              ? " — bu alan kayda hiç yazılmayacak."
              : " — bu alan için değer girilmeyecek."}
          </p>
        )}
        {Array.from({ length: count }).map((_, idx) => {
          const itemPath = `${path}[${idx}]`;
          if (isRepeatedChoice && field.choiceAlternatives) {
            const chosenAlt = repeatedChoiceAlt[itemPath] ?? resolvedDefault?.fieldName ?? field.choiceAlternatives[0];
            const altField: AsnField = chosenAlt === resolvedDefault?.fieldName && resolvedDefault
              ? resolvedDefault
              : {
                  fieldName: chosenAlt,
                  fieldType: "",
                  optional: true,
                  repeated: false,
                  tagNumber: null,
                  tagClass: null,
                  explicit: false,
                  choice: false,
                  choiceTypeName: null,
                  choiceAlternatives: null,
                  decoderHoistsImplicitChoice: false,
                  children: null,
                };
            const altValuePath = `${itemPath}.${chosenAlt}`;
            return (
              <div className="repeated-item choice-instance" key={itemPath}>
                {/* Sıra no değil indeks: istekteki anahtar "<yol>[i].<alternatif>". */}
                <div className="repeated-item-label">Instance [{idx}]</div>
                <label className="field-label" htmlFor={`${itemPath}-alt`}>
                  Alternatif
                </label>
                <select
                  id={`${itemPath}-alt`}
                  value={chosenAlt}
                  onChange={(e) => onRepeatedChoiceAltChange(itemPath, e.target.value)}
                >
                  {field.choiceAlternatives.map((alt) => (
                    <option key={alt} value={alt}>{alt}</option>
                  ))}
                </select>
                {altField.children && altField.children.length > 0 ? (
                  <FieldForm
                    fields={altField.children}
                    pathPrefix={itemPath}
                    depth={depth + 1}
                    values={values}
                    onValueChange={onValueChange}
                    repeatCounts={repeatCounts}
                    onRepeatCountChange={onRepeatCountChange}
                    repeatedChoiceAlt={repeatedChoiceAlt}
                    onRepeatedChoiceAltChange={onRepeatedChoiceAltChange}
                    onScalarChoiceChange={onScalarChoiceChange}
                    choiceUpdating={choiceUpdating}
                  />
                ) : (
                  <>
                    <label className="field-label" htmlFor={altValuePath}>
                      Değer <span className="field-type">{altField.fieldType}</span>
                    </label>
                    <LeafControl
                      field={altField}
                      path={altValuePath}
                      value={values[altValuePath] ?? ""}
                      onChange={onValueChange}
                    />
                  </>
                )}
              </div>
            );
          }
          const hasBody = !!field.children && field.children.length > 0;
          return (
            <div className="repeated-item" key={itemPath}>
              {/* Sıra no değil indeks: tekrarlı yaprak "<yol>[i]" anahtarını yazar. */}
              <div className="repeated-item-label">Instance [{idx}]</div>
              {hasBody ? (
                <FieldForm
                  fields={field.children!}
                  pathPrefix={itemPath}
                  depth={depth + 1}
                  values={values}
                  onValueChange={onValueChange}
                  repeatCounts={repeatCounts}
                  onRepeatCountChange={onRepeatCountChange}
                  repeatedChoiceAlt={repeatedChoiceAlt}
                  onRepeatedChoiceAltChange={onRepeatedChoiceAltChange}
                  onScalarChoiceChange={onScalarChoiceChange}
                  choiceUpdating={choiceUpdating}
                />
              ) : (
                <>
                  <label className="field-label" htmlFor={itemPath}>
                    Değer <span className="field-type">{field.fieldType}</span>
                  </label>
                  <LeafControl
                    field={field}
                    path={itemPath}
                    value={values[itemPath] ?? ""}
                    onChange={onValueChange}
                  />
                </>
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
    const isScalarChoice = field.choice && (field.choiceAlternatives?.length ?? 0) > 1;
    const currentAlt = field.children[0]?.fieldName ?? "";
    const choicePicker = isScalarChoice && field.choiceAlternatives && (
      <span className="inline-choice-picker" onClick={(e) => e.stopPropagation()}>
        <span className="hint-inline">alternatif ({field.choiceTypeName}):</span>{" "}
        <select
          value={currentAlt}
          disabled={choiceUpdating}
          onClick={(e) => e.stopPropagation()}
          onChange={(e) => onScalarChoiceChange(path, e.target.value)}
        >
          {field.choiceAlternatives.map((alt) => (
            <option key={alt} value={alt}>{alt}</option>
          ))}
        </select>
      </span>
    );
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
          repeatedChoiceAlt={repeatedChoiceAlt}
          onRepeatedChoiceAltChange={onRepeatedChoiceAltChange}
          onScalarChoiceChange={onScalarChoiceChange}
          choiceUpdating={choiceUpdating}
        />
      </div>
    );
    const summaryContent = (
      <>
        <strong>{field.fieldName}</strong> <TagBadge field={field} />
        {field.optional && <span className="badge badge-optional">opsiyonel</span>}
        {choicePicker}
      </>
    );
    // Derinlik 0: en üst kapsayıcı (ör. seçili CHOICE alternatifi); saklanacak
    // bir şey yok, alanları doğrudan açık gösterilir.
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
  return (
    <details className="leaf-field">
      <summary>
        {field.fieldName} <TagBadge field={field} />
        <span className="field-type">{field.fieldType}</span>
        {field.optional && <span className="badge badge-optional">opsiyonel</span>}
        {value !== "" && <span className="badge badge-filled">{value}</span>}
      </summary>
      <div className="leaf-field-body">
        <LeafControl field={field} path={path} value={value} onChange={onChange} />
      </div>
    </details>
  );
}

/** Tek bir yaprağın girdisi; türü ASN.1 tipinden seçilir. LeafInput'tan
 * ayrıldı ki tekrarlı örnekler de aynı hex/sayı/boolean girdisini kullansın. */
function LeafControl({
  field, path, value, onChange,
}: {
  field: AsnField;
  path: string;
  value: string;
  onChange: (path: string, value: string) => void;
}) {
  const kind = classifyType(field.fieldType);
  return (
    <>
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
    </>
  );
}
