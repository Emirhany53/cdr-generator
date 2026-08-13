import { useEffect, useRef, useState } from "react";
import type { GeneratingType } from "./components/OutputPanel";
import type { AsnStructure, StructureSourceMode } from "./types";
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

export default function App() {
  const [sourceMode, setSourceMode] = useState<StructureSourceMode>("existing");
  const [existingNames, setExistingNames] = useState<string[]>([]);
  const [existingNamesLoading, setExistingNamesLoading] = useState(false);
  const [selectedExistingName, setSelectedExistingName] = useState("");
  const [inlineContents, setInlineContents] = useState("");
  const [inlineStructureName, setInlineStructureName] = useState("");

  const [structure, setStructure] = useState<AsnStructure | null>(null);
  const [selectedAlternative, setSelectedAlternative] = useState("");
  const [structureLoading, setStructureLoading] = useState(false);
  const [structureError, setStructureError] = useState<string | null>(null);

  const [fieldValues, setFieldValues] = useState<Record<string, string>>({});
  const [repeatCounts, setRepeatCounts] = useState<Record<string, number>>({});

  // One generation per set of inputs, held so the .ber and .dat buttons hand out
  // the SAME bytes. Re-requesting per button would produce different records -
  // generation is random and unseeded - and the two files would stop being
  // identical. Keyed on the request so changing an input starts a fresh one.
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

  function resetFormState(next: AsnStructure) {
    setStructure(next);
    setFieldValues({});
    setRepeatCounts({});
    setSelectedAlternative(next.choiceRoot ? (next.fields[0]?.fieldName ?? "") : "");
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

  async function handleAlternativeChange(newAlternative: string) {
    if (!structure?.choiceTypeName || newAlternative === selectedAlternative) return;
    setStructureLoading(true);
    setStructureError(null);
    const selections = { [structure.choiceTypeName]: newAlternative };
    try {
      const result =
        sourceMode === "existing"
          ? await getStructureDetails(structure.structureName, selections)
          : await parseInlineStructure(inlineContents, inlineStructureName || undefined, selections);
      setStructure(result);
      setSelectedAlternative(newAlternative);
      setFieldValues({});
      setRepeatCounts({});
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

  function handleRepeatCountChange(path: string, count: number) {
    setRepeatCounts((prev) => ({ ...prev, [path]: count }));
  }

  /** The request the three buttons all describe; its JSON is the cache key. */
  function currentParams() {
    if (!structure) return null;
    const choiceSelections =
      structure.choiceRoot && structure.choiceTypeName
        ? { [structure.choiceTypeName]: selectedAlternative }
        : {};
    const trimmedFieldValues = Object.fromEntries(
      Object.entries(fieldValues)
        .map(([path, value]) => [path, value.trim()])
        .filter(([, value]) => value !== ""),
    );
    // Both formats accept the same two input modes: a registered structureName,
    // or inline ASN.1 contents (for a schema not in datastructure.json).
    return {
      structureName: sourceMode === "existing" ? structure.structureName : inlineStructureName || structure.structureName,
      contents: sourceMode === "inline" ? inlineContents : undefined,
      fieldValues: trimmedFieldValues,
      choiceSelections,
      recordCount,
    };
  }

  /**
   * Downloads one file. The BER bytes are generated once per set of inputs and
   * reused, so pressing .ber and then .dat gives two files with identical
   * content - which is the requirement. Generating again per button would not:
   * generation is random and unseeded.
   */
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
              selected={selectedAlternative}
              onChange={handleAlternativeChange}
              loading={structureLoading}
            />
          )}

          <p className="hint">
            Değer girmediğin alanlar otomatik üretilir. Sadece test etmek istediğin alanları doldurman yeterli.
          </p>

          <FieldForm
            fields={renderableFields}
            pathPrefix=""
            values={fieldValues}
            onValueChange={handleFieldValueChange}
            repeatCounts={repeatCounts}
            onRepeatCountChange={handleRepeatCountChange}
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
