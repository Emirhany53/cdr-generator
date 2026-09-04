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

/**
 * Every field path where CdrRecordBuilder.shouldSkipImplicitChoice would
 * otherwise silently drop a value the user enters - the EXACT structural
 * condition the backend itself uses (choice && !explicit && optional &&
 * decoderHoistsImplicitChoice), read straight off the field tree. Not a
 * heuristic ("multiple alternatives picked", "this looks repeated") - a
 * literal mirror of the backend rule, so it can never disagree with what the
 * backend actually does. A path here means: a value under it requires
 * referenceMode:true to survive generation at all.
 */
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

/**
 * Does `key` address an instance of `path` at or beyond `count` - i.e. one of
 * the instances a shrinking repeat count just removed from the form?
 *
 * <p>Matches the index form both value maps use: "path[i].leaf" and any deeper
 * key under it (a nested collection inside the removed instance), plus the bare
 * "path[i]" that keys a repeated-CHOICE instance's chosen alternative.</p>
 */
function addressesRemovedInstance(key: string, path: string, count: number): boolean {
  const prefix = `${path}[`;
  if (!key.startsWith(prefix)) return false;
  const close = key.indexOf("]", prefix.length);
  if (close < 0) return false;
  const index = Number(key.slice(prefix.length, close));
  return Number.isInteger(index) && index >= count;
}

/** Drops every entry addressing an instance the new repeat count removed.
 * Returns the SAME object when nothing matched, so React skips the re-render. */
function pruneRemovedInstances<T>(
  entries: Record<string, T>, path: string, count: number,
): Record<string, T> {
  const stale = Object.keys(entries).filter((key) => addressesRemovedInstance(key, path, count));
  if (stale.length === 0) return entries;
  const next = { ...entries };
  for (const key of stale) delete next[key];
  return next;
}

/** True iff some fieldValues key falls under one of the required paths -
 * "<path>.<leaf>" (scalar) or "<path>[<idx>].<leaf>" (repeated). */
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
  // Every CHOICE pick made so far - keyed by the root's CHOICE type name for
  // the root picker, or by a nested SCALAR field's own PATH for a picker on
  // that field. Both keys are sent to the backend in the same
  // choiceSelections map: StructureParserService.rewriteChoiceAlternatives
  // reads a path key for ONE call site and leaves every other key working as
  // a type-name (global) pick, exactly like ChoicePicker already relied on
  // for the root. Repeated CHOICE fields do NOT use this - see
  // repeatedChoiceAlt below.
  const [choiceSelections, setChoiceSelections] = useState<Record<string, string>>({});
  const [structureLoading, setStructureLoading] = useState(false);
  const [structureError, setStructureError] = useState<string | null>(null);

  const [fieldValues, setFieldValues] = useState<Record<string, string>>({});
  const [repeatCounts, setRepeatCounts] = useState<Record<string, number>>({});
  // Alternative chosen for each repeated-CHOICE instance, keyed by that
  // instance's own path (e.g. "list-Of-Calling-Party-Address[1]"). Unlike
  // choiceSelections above, this never triggers a refetch - it only decides
  // which alternative name the instance's value input is keyed under
  // (itemPath + "." + altName), which the backend's indexed expansion reads.
  const [repeatedChoiceAlt, setRepeatedChoiceAlt] = useState<Record<string, string>>({});

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

  /**
   * Picks a different CHOICE alternative for a SCALAR (non-repeated) CHOICE
   * field and refetches the structure with it. `key` is either the root's
   * CHOICE type name (ChoicePicker) or a nested field's own path (FieldForm's
   * inline picker) - StructureParserService accepts both in the same map.
   * `resetPrefix` says which of the values the user already typed are now
   * stale: undefined clears EVERYTHING (a root change can reshape the whole
   * tree, matching the previous behaviour), a path clears only entries under
   * that one field's subtree.
   */
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

  /**
   * Sets a repeated field's instance count AND forgets everything the removed
   * instances held. Without the second half the values stayed in state, kept
   * being sent, and the backend built instances the form no longer showed:
   * filling [0] and [1] then pressing "−" still posted
   * "...[1].tEL-URI", which indexedGroupCount reads as a second instance. The
   * count is the user's statement of how many there are; state that outlives it
   * is not data they can see, correct or delete.
   *
   * <p>Applies to EVERY repeated field, not only a repeated CHOICE: the same
   * divergence was reproduced on interOperatorIdentifiers, an ordinary
   * SEQUENCE OF. Nested collections inside a removed instance go with it, so
   * re-adding the instance starts empty rather than resurrecting old values.</p>
   */
  function handleRepeatCountChange(path: string, count: number) {
    setRepeatCounts((prev) => pruneRemovedInstances({ ...prev, [path]: count }, path, count));
    setFieldValues((prev) => pruneRemovedInstances(prev, path, count));
    setRepeatedChoiceAlt((prev) => pruneRemovedInstances(prev, path, count));
  }

  function handleRepeatedChoiceAltChange(itemPath: string, alternativeName: string) {
    setRepeatedChoiceAlt((prev) => ({ ...prev, [itemPath]: alternativeName }));
    // The value the user typed for the PREVIOUS alternative at this instance
    // belongs to a key ("itemPath.oldAlt") the new alternative will never
    // read - drop it so it doesn't linger unsent and confuse a re-check.
    setFieldValues((prev) => {
      const next = { ...prev };
      for (const path of Object.keys(next)) {
        if (path.startsWith(`${itemPath}.`)) delete next[path];
      }
      return next;
    });
  }

  /** The request the three buttons all describe; its JSON is the cache key. */
  function currentParams() {
    if (!structure) return null;
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
      referenceMode: needsReferenceMode(trimmedFieldValues, referenceModeRequiredPaths),
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
