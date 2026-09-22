// Rendered from DashboardSummaryResponse pushed on /topic/dashboard (AGENT.md §5).
const CARDS = [
  ['totalOrders', 'Total Orders'],
  ['pending', 'Pending'],
  ['processing', 'Processing'],
  ['completed', 'Completed'],
  ['outOfStock', 'Out of Stock'],
  ['failed', 'Failed'],
  ['dlq', 'Dead-Lettered'],
];

export default function DashboardSummary({ summary }) {
  if (!summary) return null;
  return (
    <section className="panel">
      <h2>Summary</h2>
      <div className="summary-cards">
        {CARDS.map(([key, label]) => (
          <div key={key} className="summary-card">
            <span className="summary-value">{summary[key] ?? 0}</span>
            <span className="summary-label">{label}</span>
          </div>
        ))}
      </div>
    </section>
  );
}
