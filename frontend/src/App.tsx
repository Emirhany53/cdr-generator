import { useEffect, useMemo, useRef, useState } from "react";
import type { GeneratingType } from "./components/OutputPanel";
import type { AsnField, AsnStructure, StructureSourceMode } from "./types";
import {
  ApiError,
  generateText,
  generateBer,
  getStructureDetails,
  getStructureNames,
  parseInlineStructure,
  triggerBrowserDownload,
  type DownloadedFile,
} from "./api/client";
import StructureSourcePicker from "./components/StructureSourcePicker";
import ChoicePicker from "./components/ChoicePicker";
import FieldForm from "./components/FieldForm";
import OutputPanel from "./components/OutputPanel";
import Banner from "./components/Banner";
import "./App.css";

/** referenceMode gerektiren alan yolları: backend'in shouldSkipImplicitChoice
 * koşulunun aynısı (choice && !explicit && optional && decoderHoistsImplicitChoice),
 * doğrudan alan ağacından okunur. Bu yollardan birine değer girilirse
 * referenceMode açılmazsa değer sessizce üretilmez. */
function collectReferenceModeRequiredPaths(fields: AsnField[], prefix: string, out: Set<string>) {
  for (const field of fields) {
    const path = prefix ? `${prefix}.${field.fieldName}` : field.fieldName;
    if (field.choice && !field.explicit && field.optional && field.decoderHoistsImplicitChoice) {
      out.add(path);
    }
    if (field.children && field.children.length > 0) {
      collectReferenceModeRequiredPaths(field.children, path, out);
    }
  }
}

/** `key`, `path`'in `count` ve sonrasındaki bir örneğini mi adresliyor —
 * yani sayaç küçültülünce formdan kalkan örneklerden birini? "path[i].yaprak",
 * altındaki daha derin anahtarlar ve çıplak "path[i]" kapsanır. */
function addressesRemovedInstance(key: string, path: string, count: number): boolean {
  const prefix = `${path}[`;
  if (!key.startsWith(prefix)) return false;
  const close = key.indexOf("]", prefix.length);
  if (close < 0) return false;
  const index = Number(key.slice(prefix.length, close));
  return Number.isInteger(index) && index >= count;
}

/** Kalkan örneklere ait girdileri atar. Eşleşme yoksa aynı nesneyi döndürür
 * ki React gereksiz yere yeniden çizmesin. */
function pruneRemovedInstances<T>(
  entries: Record<string, T>, path: string, count: number,
): Record<string, T> {
  const stale = Object.keys(entries).filter((key) => addressesRemovedInstance(key, path, count));
  if (stale.length === 0) return entries;
  const next = { ...entries };
  for (const key of stale) delete next[key];
  return next;
}

/** fieldValues'ta bu yollardan birinin altında değer var mı? */
function needsReferenceMode(fieldValues: Record<string, string>, requiredPaths: Set<string>): boolean {
  if (requiredPaths.size === 0) return false;
  for (const key of Object.keys(fieldValues)) {
    for (const required of requiredPaths) {
      if (key.startsWith(`${required}.`) || key.startsWith(`${required}[`)) {
        return true;
      }
    }
  }
  return false;
}

export default function App() {
  const [sourceMode, setSourceMode] = useState<StructureSourceMode>("existing");
  const [existingNames, setExistingNames] = useState<string[]>([]);
  const [existingNamesLoading, setExistingNamesLoading] = useState(false);
  const [selectedExistingName, setSelectedExistingName] = useState("");
  const [inlineContents, setInlineContents] = useState("");
  const [inlineStructureName, setInlineStructureName] = useState("");

  const [structure, setStructure] = useState<AsnStructure | null>(null);
  // Yapılan CHOICE seçimleri. Kök için CHOICE tipinin adı, iç içe SKALER bir
  // alan için o alanın yolu anahtardır; backend ikisini de aynı haritada kabul
  // eder (yol anahtarı tek çağrı yerini etkiler). Tekrarlı CHOICE bunu
  // kullanmaz -> repeatedChoiceAlt.
  const [choiceSelections, setChoiceSelections] = useState<Record<string, string>>({});
  const [structureLoading, setStructureLoading] = useState(false);
  const [structureError, setStructureError] = useState<string | null>(null);

  const [fieldValues, setFieldValues] = useState<Record<string, string>>({});
  const [repeatCounts, setRepeatCounts] = useState<Record<string, number>>({});
  // Tekrarlı CHOICE'ta her örneğin seçili alternatifi; anahtar örneğin yolu.
  // Yeniden çekme yapmaz, yalnızca değerin hangi anahtara yazılacağını belirler
  // (örnekYolu + "." + alternatifAdı).
  const [repeatedChoiceAlt, setRepeatedChoiceAlt] = useState<Record<string, string>>({});

  // Aynı girdi kümesi için tek üretim: .ber ve .dat düğmeleri AYNI baytları
  // versin diye. Üretim tohumsuz/rastgele olduğundan düğme başına yeniden
  // istek atmak iki dosyayı farklılaştırırdı. Anahtar isteğin kendisidir.
  const cache = useRef<{ key: string; ber?: DownloadedFile; text?: DownloadedFile }>({ key: "" });
  const [recordCount, setRecordCount] = useState(1);
  const [generatingType, setGeneratingType] = useState<GeneratingType>(null);
  const [generateError, setGenerateError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  useEffect(() => {
    setExistingNamesLoading(true);
    getStructureNames()
      .then((names) => setExistingNames(names))
      .catch((err) => setStructureError(err instanceof Error ? err.message : String(err)))
      .finally(() => setExistingNamesLoading(false));
  }, []);

  const referenceModeRequiredPaths = useMemo(() => {
    const out = new Set<string>();
    if (structure) collectReferenceModeRequiredPaths(structure.fields, "", out);
    return out;
  }, [structure]);

  function resetFormState(next: AsnStructure) {
    setStructure(next);
    setFieldValues({});
    setRepeatCounts({});
    setRepeatedChoiceAlt({});
    setChoiceSelections(
      next.choiceRoot && next.choiceTypeName
        ? { [next.choiceTypeName]: next.fields[0]?.fieldName ?? "" }
        : {},
    );
  }

  async function handleLoadExisting() {
    if (!selectedExistingName) return;
    setStructureLoading(true);
    setStructureError(null);
    setGenerateError(null);
    setSuccessMessage(null);
    try {
      const result = await getStructureDetails(selectedExistingName);
      resetFormState(result);
    } catch (err) {
      setStructureError(err instanceof ApiError ? err.message : String(err));
      setStructure(null);
    } finally {
      setStructureLoading(false);
    }
  }

  async function handleParseInline() {
    if (!inlineContents.trim()) return;
    setStructureLoading(true);
    setStructureError(null);
    setGenerateError(null);
    setSuccessMessage(null);
    try {
      const result = await parseInlineStructure(inlineContents, inlineStructureName || undefined);
      resetFormState(result);
    } catch (err) {
      setStructureError(err instanceof ApiError ? err.message : String(err));
      setStructure(null);
    } finally {
      setStructureLoading(false);
    }
  }

  /** Skaler bir CHOICE'ın alternatifini değiştirir ve yapıyı yeniden çeker.
   * `key` kökte tip adı, iç alanda alan yoludur. `resetPrefix` verilmezse tüm
   * girilen değerler silinir (kök değişimi ağacı baştan kurar), verilirse
   * yalnızca o alanın altındakiler. */
  async function handleChoiceSelectionChange(key: string, newAlternative: string, resetPrefix?: string) {
    if (!structure || choiceSelections[key] === newAlternative) return;
    setStructureLoading(true);
    setStructureError(null);
    const nextSelections = { ...choiceSelections, [key]: newAlternative };
    try {
      const result =
        sourceMode === "existing"
          ? await getStructureDetails(structure.structureName, nextSelections)
          : await parseInlineStructure(inlineContents, inlineStructureName || undefined, nextSelections);
      setStructure(result);
      setChoiceSelections(nextSelections);
      if (resetPrefix === undefined) {
        setFieldValues({});
        setRepeatCounts({});
        setRepeatedChoiceAlt({});
      } else {
        const isStale = (path: string) =>
          path === resetPrefix || path.startsWith(`${resetPrefix}.`) || path.startsWith(`${resetPrefix}[`);
        setFieldValues((prev) => {
          const next = { ...prev };
          for (const path of Object.keys(next)) {
            if (isStale(path)) delete next[path];
          }
          return next;
        });
        setRepeatedChoiceAlt((prev) => {
          const next = { ...prev };
          for (const path of Object.keys(next)) {
            if (isStale(path)) delete next[path];
          }
          return next;
        });
      }
    } catch (err) {
      setStructureError(err instanceof ApiError ? err.message : String(err));
    } finally {
      setStructureLoading(false);
    }
  }

  function handleFieldValueChange(path: string, value: string) {
    setFieldValues((prev) => {
      if (value === "") {
        const next = { ...prev };
        delete next[path];
        return next;
      }
      return { ...prev, [path]: value };
    });
  }

  /** Örnek sayısını ayarlar ve kalkan örneklerin değerlerini de siler. Aksi
   * halde form 1 örnek gösterirken istek hâlâ [1], [2] anahtarlarını taşıyor,
   * backend görünmeyen örnekleri üretiyordu. Tüm tekrarlı alanlar için geçerli;
   * kalkan örneğin içindeki alt listeler de silinir. */
  function handleRepeatCountChange(path: string, count: number) {
    setRepeatCounts((prev) => pruneRemovedInstances({ ...prev, [path]: count }, path, count));
    setFieldValues((prev) => pruneRemovedInstances(prev, path, count));
    setRepeatedChoiceAlt((prev) => pruneRemovedInstances(prev, path, count));
  }

  function handleRepeatedChoiceAltChange(itemPath: string, alternativeName: string) {
    setRepeatedChoiceAlt((prev) => ({ ...prev, [itemPath]: alternativeName }));
    // Önceki alternatife girilen değer artık okunmayacak bir anahtarda kalır.
    setFieldValues((prev) => {
      const next = { ...prev };
      for (const path of Object.keys(next)) {
        if (path.startsWith(`${itemPath}.`)) delete next[path];
      }
      return next;
    });
  }

  /** Üç indirme düğmesinin de tarif ettiği istek; JSON'u cache anahtarıdır. */
  function currentParams() {
    if (!structure) return null;
    const trimmedFieldValues = Object.fromEntries(
      Object.entries(fieldValues)
        .map(([path, value]) => [path, value.trim()])
        .filter(([, value]) => value !== ""),
    );
    // İki format da aynı iki girdi biçimini kabul eder: kayıtlı structureName
    // ya da inline ASN.1 metni.
    return {
      structureName: sourceMode === "existing" ? structure.structureName : inlineStructureName || structure.structureName,
      contents: sourceMode === "inline" ? inlineContents : undefined,
      fieldValues: trimmedFieldValues,
      choiceSelections,
      recordCount,
      referenceMode: needsReferenceMode(trimmedFieldValues, referenceModeRequiredPaths),
    };
  }

  /** Tek dosya indirir. BER baytları girdi kümesi başına bir kez üretilip
   * yeniden kullanılır; .ber ve .dat böylece birebir aynı içeriği taşır. */
  async function handleDownload(what: "ber" | "dat" | "txt") {
    const params = currentParams();
    if (!params) return;

    const key = JSON.stringify(params);
    if (cache.current.key !== key) {
      cache.current = { key };
    }

    setGeneratingType(what);
    setGenerateError(null);
    setSuccessMessage(null);
    try {
      if (what === "txt") {
        cache.current.text ??= await generateText(params);
        const file = cache.current.text;
        triggerBrowserDownload(file);
        setSuccessMessage(`${file.fileName} indirildi.`);
        return;
      }
      cache.current.ber ??= await generateBer(params);
      const file = cache.current.ber;
      const name = file.fileName.replace(/\.(ber|dat)$/i, `.${what}`);
      triggerBrowserDownload(file, name);
      setSuccessMessage(`${name} indirildi.`);
    } catch (err) {
      setGenerateError(err instanceof ApiError ? err.message : String(err));
    } finally {
      setGeneratingType(null);
    }
  }

  const renderableFields = structure?.fields ?? [];
  const referenceModeActive = needsReferenceMode(fieldValues, referenceModeRequiredPaths);

  return (
    <div className="app-shell">
      <header className="app-header">
        <h1>EMM CDR Generator</h1>
        <p>ASN.1 şemasından test CDR dosyası üret — alanlara istediğin değerleri gir, geri kalanı otomatik doldurulsun.</p>
      </header>

      {structureError && (
        <Banner kind="error" onDismiss={() => setStructureError(null)}>
          {structureError}
        </Banner>
      )}

      <StructureSourcePicker
        mode={sourceMode}
        onModeChange={(m) => {
          setSourceMode(m);
          setStructure(null);
          setStructureError(null);
        }}
        existingNames={existingNames}
        existingNamesLoading={existingNamesLoading}
        selectedExistingName={selectedExistingName}
        onSelectedExistingNameChange={setSelectedExistingName}
        onLoadExisting={handleLoadExisting}
        inlineContents={inlineContents}
        onInlineContentsChange={setInlineContents}
        inlineStructureName={inlineStructureName}
        onInlineStructureNameChange={setInlineStructureName}
        onParseInline={handleParseInline}
        loading={structureLoading}
      />

      {structure && (
        <section className="card">
          <h2>2. Alan değerleri — {structure.structureName}</h2>

          {structure.choiceRoot && structure.choiceTypeName && structure.choiceAlternatives && (
            <ChoicePicker
              choiceTypeName={structure.choiceTypeName}
              alternatives={structure.choiceAlternatives}
              selected={choiceSelections[structure.choiceTypeName] ?? ""}
              onChange={(alt) => handleChoiceSelectionChange(structure.choiceTypeName!, alt)}
              loading={structureLoading}
            />
          )}

          <p className="hint">
            Değer girmediğin alanlar otomatik üretilir. Sadece test etmek istediğin alanları doldurman yeterli.
            Bir alanın yanında <strong>alternatif</strong> seçici varsa, o alan bir CHOICE'tır (ör. sIP-URI /
            tEL-URI) — hangi dalın üretileceğini oradan seçebilirsin.
          </p>
          {referenceModeActive && (
            <p className="hint hint-warning">
              Bu kayıtta doldurmadığın diğer opsiyonel alanlar otomatik üretilmeyip boş bırakılacak
              — çünkü işaretlediğin alanlardan en az biri, değer verilmezse zaten hiç üretilmeyen
              türden (referans modu bu yüzden otomatik devrede).
            </p>
          )}

          <FieldForm
            fields={renderableFields}
            pathPrefix=""
            values={fieldValues}
            onValueChange={handleFieldValueChange}
            repeatCounts={repeatCounts}
            onRepeatCountChange={handleRepeatCountChange}
            repeatedChoiceAlt={repeatedChoiceAlt}
            onRepeatedChoiceAltChange={handleRepeatedChoiceAltChange}
            onScalarChoiceChange={(path, alt) => handleChoiceSelectionChange(path, alt, path)}
            choiceUpdating={structureLoading}
          />
        </section>
      )}

      {structure && (
        <>
          {generateError && (
            <Banner kind="error" onDismiss={() => setGenerateError(null)}>
              {generateError}
            </Banner>
          )}
          {successMessage && (
            <Banner kind="success" onDismiss={() => setSuccessMessage(null)}>
              {successMessage}
            </Banner>
          )}
          <OutputPanel
            sourceMode={sourceMode}
            onDownloadBer={() => handleDownload("ber")}
            onDownloadDat={() => handleDownload("dat")}
            onDownloadText={() => handleDownload("txt")}
            recordCount={recordCount}
            onRecordCountChange={setRecordCount}
            generatingType={generatingType}
            disabled={!structure}
          />
        </>
      )}
    </div>
  );
}
