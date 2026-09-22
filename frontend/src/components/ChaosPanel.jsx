import { useState } from 'react';

const BURST_SIZES = [10, 25, 100];

/**
 * Operational tooling that makes the system's concurrency and failure behaviour visible on the
 * dashboard instead of only in the logs:
 *
 * - **Burst** fires far more simultaneous orders than the bounded worker pool can hold, so the live
 *   feed shows the backlog draining and any rejected submission (503 POOL_SATURATED) is reported
 *   rather than silently dropped.
 * - **Fault injection** arms a bounded number of transient failures for one product, which walks an
 *   order through the real retry ladder (PROCESSING -> FAILED -> ... -> DLQ) and then back to
 *   COMPLETED via the DLQ replay button. It is a gated backend capability: when the endpoint is not
 *   exposed the controls are hidden rather than shown as broken buttons.
 */
export default function ChaosPanel({ products, faultInjectionEnabled, onBurst, onArmFault, onClearFault }) {
  const [productId, setProductId] = useState('');
  const [burstSize, setBurstSize] = useState(25);
  const [occurrences, setOccurrences] = useState(3);
  const [busy, setBusy] = useState(false);
  const [burstResult, setBurstResult] = useState(null);
  const [note, setNote] = useState(null);
  const [error, setError] = useState(null);

  async function run(action) {
    if (!productId) {
      setError('Select a product first');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await action(Number(productId));
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  function handleBurst() {
    setBurstResult(null);
    setNote(null);
    return run(async (id) => {
      const result = await onBurst(id, 1, burstSize);
      setBurstResult(result);
    });
  }

  function handleArm() {
    setBurstResult(null);
    setNote(null);
    return run(async (id) => {
      await onArmFault(id, occurrences);
      setNote(`${occurrences} transient fault(s) armed for product ${id} — submit an order to watch the retry ladder`);
    });
  }

  function handleClear() {
    setBurstResult(null);
    setNote(null);
    return run(async (id) => {
      await onClearFault(id);
      setNote(`Faults cleared for product ${id} — the pipeline is back to normal`);
    });
  }

  return (
    <section className="panel">
      <h2>Load &amp; Failure Drills</h2>
      <div className="chaos-form">
        <select value={productId} onChange={(e) => setProductId(e.target.value)}>
          <option value="">Select product...</option>
          {products.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name} ({p.quantity ?? '?'} in stock)
            </option>
          ))}
        </select>

        <select value={burstSize} onChange={(e) => setBurstSize(Number(e.target.value))}>
          {BURST_SIZES.map((size) => (
            <option key={size} value={size}>
              burst ×{size}
            </option>
          ))}
        </select>
        <button type="button" onClick={handleBurst} disabled={busy}>
          {busy ? 'Running...' : 'Fire burst'}
        </button>

        {faultInjectionEnabled && (
          <>
            <input
              type="number"
              min="1"
              max="20"
              value={occurrences}
              onChange={(e) => setOccurrences(Number(e.target.value))}
              title="Number of transient failures to inject"
            />
            <button type="button" className="ghost-btn" onClick={handleArm} disabled={busy}>
              Inject faults
            </button>
            <button type="button" className="ghost-btn" onClick={handleClear} disabled={busy}>
              Clear faults
            </button>
          </>
        )}
      </div>

      {burstResult && (
        <p className="drill-result">
          requested {burstResult.requested} · accepted {burstResult.accepted} · rejected {burstResult.rejected}
          {burstResult.failures.length > 0 && <> — {burstResult.failures.join(' | ')}</>}
        </p>
      )}
      {note && <p className="drill-note">{note}</p>}
      {error && <p className="error-text">{error}</p>}
      {!faultInjectionEnabled && (
        <p className="drill-hint">
          Fault injection is switched off on this backend (`order.ops.fault-injection.enabled=false`), so
          the retry/DLQ drill is hidden. The burst drill works either way.
        </p>
      )}
    </section>
  );
}