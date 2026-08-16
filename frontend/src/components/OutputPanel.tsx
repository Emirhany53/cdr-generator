import { useEffect, useState } from "react";
import type { ChangeEvent } from "react";
import type { StructureSourceMode } from "../types";

export type GeneratingType = "ber" | "dat" | "txt" | null;

const MIN_RECORD_COUNT = 1;
const MAX_RECORD_COUNT = 100;

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

  /**
   * The text being typed, which is not always a number.
   *
   * The field used to push `Number(e.target.value)` straight into state, and
   * `Number("")` is 0 — so clearing the box to type a new count produced a 0
   * that could not be removed, and every digit after it landed on its right:
   * "0" then "05" then "052". Holding the raw text lets the box be empty while
   * the user types; blur is where it becomes a number again.
   */
  const [draft, setDraft] = useState(String(recordCount));

  // Follow the count when something else changes it, but never rewrite what is
  // being typed: "05" already means 5, and rewriting it would move the caret.
  useEffect(() => {
    if (Number(draft) !== recordCount) {
      setDraft(String(recordCount));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recordCount]);

  function handleRecordCountInput(event: ChangeEvent<HTMLInputElement>) {
    const raw = event.target.value;
    setDraft(raw);
    const parsed = Number(raw);
    if (raw !== "" && Number.isInteger(parsed)) {
      onRecordCountChange(parsed);
    }
  }

  /** Leaving the field settles it: empty or out of range becomes a usable count. */
  function handleRecordCountBlur() {
    const parsed = Number(draft);
    const settled = draft.trim() === "" || !Number.isFinite(parsed)
      ? MIN_RECORD_COUNT
      : Math.min(MAX_RECORD_COUNT, Math.max(MIN_RECORD_COUNT, Math.trunc(parsed)));
    setDraft(String(settled));
    onRecordCountChange(settled);
  }

  return (
    <section className="card">
      <h2>3. Çıktı</h2>

      <label className="field-label" htmlFor="record-count">
        Kayıt sayısı (1–100)
      </label>
      <input
        id="record-count"
        type="number"
        min={MIN_RECORD_COUNT}
        max={MAX_RECORD_COUNT}
        value={draft}
        onChange={handleRecordCountInput}
        onBlur={handleRecordCountBlur}
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
