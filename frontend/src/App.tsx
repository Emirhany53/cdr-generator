import { useEffect, useState } from "react";
import type { AsnStructure, OutputFormat, StructureSourceMode } from "./types";
import {
  ApiError,
  generateAscii,
  generateBer,
  getStructureDetails,
  getStructureNames,
  parseInlineStructure,
  triggerBrowserDownload,
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

  const [format, setFormat] = useState<OutputFormat>("ber");
  const [recordCount, setRecordCount] = useState(1);
  const [generating, setGenerating] = useState(false);
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

  async function handleGenerate() {
    if (!structure) return;
    setGenerating(true);
    setGenerateError(null);
    setSuccessMessage(null);
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
    const commonParams = {
      structureName: sourceMode === "existing" ? structure.structureName : inlineStructureName || structure.structureName,
      contents: sourceMode === "inline" ? inlineContents : undefined,
      fieldValues: trimmedFieldValues,
      choiceSelections,
      recordCount,
    };

    try {
      const file = format === "ascii" ? await generateAscii(commonParams) : await generateBer(commonParams);
      triggerBrowserDownload(file);
      setSuccessMessage(`${file.fileName} indirildi.`);
    } catch (err) {
      setGenerateError(err instanceof ApiError ? err.message : String(err));
    } finally {
      setGenerating(false);
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
            format={format}
            onFormatChange={setFormat}
            recordCount={recordCount}
            onRecordCountChange={setRecordCount}
            onGenerate={handleGenerate}
            generating={generating}
            disabled={!structure}
          />
        </>
      )}
    </div>
  );
}
