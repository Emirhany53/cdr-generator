interface ChoicePickerProps {
  choiceTypeName: string;
  alternatives: string[];
  selected: string;
  onChange: (alternative: string) => void;
  loading: boolean;
}

export default function ChoicePicker({
  choiceTypeName, alternatives, selected, onChange, loading,
}: ChoicePickerProps) {
  return (
    <div className="choice-picker">
      <label className="field-label" htmlFor="choice-alternative">
        Bu yapının kökü bir <strong>CHOICE</strong> ({choiceTypeName}) — hangi dal üretilsin?
      </label>
      <select
        id="choice-alternative"
        value={selected}
        onChange={(e) => onChange(e.target.value)}
        disabled={loading}
      >
        {alternatives.map((alt) => (
          <option key={alt} value={alt}>
            {alt}
          </option>
        ))}
      </select>
      {loading && <span className="hint">Dal değiştiriliyor…</span>}
    </div>
  );
}
