import { useMemo, useState } from "react";
import type { ChangeEvent } from "react";
import type { StructureSourceMode } from "../types";

interface StructureSourcePickerProps {
  mode: StructureSourceMode;
  onModeChange: (mode: StructureSourceMode) => void;

  existingNames: string[];
  existingNamesLoading: boolean;
  selectedExistingName: string;
  onSelectedExistingNameChange: (name: string) => void;
  onLoadExisting: () => void;

  inlineContents: string;
  onInlineContentsChange: (contents: string) => void;
  inlineStructureName: string;
  onInlineStructureNameChange: (name: string) => void;
  onParseInline: () => void;

  loading: boolean;
}

export default function StructureSourcePicker(props: StructureSourcePickerProps) {
  const {
    mode, onModeChange,
    existingNames, existingNamesLoading, selectedExistingName, onSelectedExistingNameChange, onLoadExisting,
    inlineContents, onInlineContentsChange, inlineStructureName, onInlineStructureNameChange, onParseInline,
    loading,
  } = props;

  const [search, setSearch] = useState("");
  const filteredNames = useMemo(() => {
    if (!search.trim()) return existingNames;
    const needle = search.trim().toLowerCase();
    return existingNames.filter((n) => n.toLowerCase().includes(needle));
  }, [existingNames, search]);

  function handleFileChange(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      onInlineContentsChange(String(reader.result ?? ""));
      if (!inlineStructureName) {
        onInlineStructureNameChange(file.name.replace(/\.[^.]+$/, ""));
      }
    };
    reader.readAsText(file);
    // allow re-selecting the same file later
    e.target.value = "";
  }

  return (
    <section className="card">
      <h2>1. Şema Kaynağı</h2>
      <div className="tabs">
        <button
          type="button"
          className={mode === "existing" ? "tab tab-active" : "tab"}
          onClick={() => onModeChange("existing")}
        >
          Kayıtlı Yapıdan Seç
        </button>
        <button
          type="button"
          className={mode === "inline" ? "tab tab-active" : "tab"}
          onClick={() => onModeChange("inline")}
        >
          Dosya Yükle / Metin Yapıştır
        </button>
      </div>

      {mode === "existing" ? (
        <div className="tab-panel">
          <label className="field-label" htmlFor="structure-search">
            Yapı ara ({existingNames.length} yapı)
          </label>
          <input
            id="structure-search"
            type="text"
            placeholder="örn. AIR2CSN"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            disabled={existingNamesLoading}
          />
          <select
            size={8}
            value={selectedExistingName}
            onChange={(e) => onSelectedExistingNameChange(e.target.value)}
            disabled={existingNamesLoading}
          >
            {filteredNames.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
          <button
            type="button"
            className="btn btn-primary"
            onClick={onLoadExisting}
            disabled={!selectedExistingName || loading}
          >
            {loading ? "Yükleniyor…" : "Alanları getir"}
          </button>
        </div>
      ) : (
        <div className="tab-panel">
          <label className="field-label" htmlFor="inline-name">
            Görünen ad (opsiyonel)
          </label>
          <input
            id="inline-name"
            type="text"
            placeholder="örn. DemoVoice"
            value={inlineStructureName}
            onChange={(e) => onInlineStructureNameChange(e.target.value)}
          />

          <label className="field-label" htmlFor="inline-file">
            .asn1 / .txt dosyası yükle
          </label>
          <input id="inline-file" type="file" accept=".asn1,.asn,.txt" onChange={handleFileChange} />

          <label className="field-label" htmlFor="inline-text">
            veya şema metnini buraya yapıştır
          </label>
          <textarea
            id="inline-text"
            rows={10}
            placeholder={"Demo DEFINITIONS IMPLICIT TAGS ::=\nBEGIN\n  ...\nEND"}
            value={inlineContents}
            onChange={(e) => onInlineContentsChange(e.target.value)}
          />
          <button
            type="button"
            className="btn btn-primary"
            onClick={onParseInline}
            disabled={!inlineContents.trim() || loading}
          >
            {loading ? "Ayrıştırılıyor…" : "Alanları getir"}
          </button>
        </div>
      )}
    </section>
  );
}
