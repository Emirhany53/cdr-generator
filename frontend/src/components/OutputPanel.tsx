import type { StructureSourceMode } from "../types";

export type GeneratingType = "ber" | "dat" | "txt" | null;

interface OutputPanelProps {
  sourceMode: StructureSourceMode;
  recordCount: number;
  onRecordCountChange: (count: number) => void;
  generatingType: GeneratingType;
  disabled: boolean;
  onDownloadBer: () => void;
  onDownloadDat: () => void;
  onDownloadText: () => void;
}

/**
 * Three outputs, three buttons, one file each.
 *
 * <p>.ber and .dat carry the same bytes: the BER file is generated once per set
 * of inputs and reused, so pressing both gives two identical files. .txt is the
 * pipe-separated text output and comes from its own endpoint.</p>
 */
export default function OutputPanel({
  recordCount, onRecordCountChange, generatingType, disabled,
  onDownloadBer, onDownloadDat, onDownloadText,
}: OutputPanelProps) {
  const busy = disabled || generatingType !== null;

  return (
    <section className="card">
      <h2>3. Çıktı</h2>

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

      <p className="field-label">Dosya türü</p>
      <div className="download-choice">
        <button type="button" className="btn btn-primary" onClick={onDownloadBer} disabled={busy}>
          {generatingType === "ber" ? "Oluşturuluyor…" : "BER indir (.ber)"}
        </button>
        <button type="button" className="btn btn-primary" onClick={onDownloadDat} disabled={busy}>
          {generatingType === "dat" ? "Oluşturuluyor…" : "DAT indir (.dat)"}
        </button>
        <button type="button" className="btn btn-primary" onClick={onDownloadText} disabled={busy}>
          {generatingType === "txt" ? "Oluşturuluyor…" : "TEXT indir (.txt)"}
        </button>
      </div>
      <p className="hint">
        .ber ve .dat aynı baytları taşır — ikisini de indirirsen içerikleri birebir aynıdır.
        .txt ayrı bir metin çıktısıdır.
      </p>
    </section>
  );
}
