import type { OutputFormat, StructureSourceMode } from "../types";

interface OutputPanelProps {
  sourceMode: StructureSourceMode;
  format: OutputFormat;
  onFormatChange: (format: OutputFormat) => void;
  recordCount: number;
  onRecordCountChange: (count: number) => void;
  onGenerate: () => void;
  generating: boolean;
  disabled: boolean;
}

export default function OutputPanel({
  format, onFormatChange, recordCount, onRecordCountChange, onGenerate, generating, disabled,
}: OutputPanelProps) {
  return (
    <section className="card">
      <h2>3. Çıktı</h2>

      <div className="format-choice">
        <label>
          <input
            type="radio"
            name="format"
            checked={format === "ascii"}
            onChange={() => onFormatChange("ascii")}
          />
          ASCII (.txt)
        </label>
        <label>
          <input
            type="radio"
            name="format"
            checked={format === "ber"}
            onChange={() => onFormatChange("ber")}
          />
          BER (.ber)
        </label>
      </div>

      <label className="field-label" htmlFor="record-count">
        Kayıt sayısı (1–100)
      </label>
      <input
        id="record-count"
        type="number"
        min={1}
        max={100}
        value={recordCount}
        onChange={(e) => onRecordCountChange(Number(e.target.value))}
      />

      <button type="button" className="btn btn-primary btn-generate" onClick={onGenerate} disabled={disabled || generating}>
        {generating ? "Oluşturuluyor…" : "Oluştur ve İndir"}
      </button>
    </section>
  );
}
