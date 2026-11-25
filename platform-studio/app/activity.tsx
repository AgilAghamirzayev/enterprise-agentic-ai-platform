import { Row, label } from "./client";

export function Activity({ usage }: { usage: Row }) {
  if (!Array.isArray(usage.hourly)) return null;
  const buckets = usage.hourly as Row[];
  const maximum = Math.max(1, ...buckets.map((b) => Number(b.total)));
  const format = (b: Row) =>
    new Date(label(b.startedAt)).toLocaleTimeString([], {
      hour: "2-digit",
      minute: "2-digit",
    });
  return (
    <section className="panel">
      <div className="split">
        <h2>Execution volume</h2>
        <span className="range">Last 24 hours</span>
      </div>
      <div
        className="bars"
        role="img"
        aria-label={
          "Executions by hour. " +
          buckets
            .map(
              (b) =>
                format(b) + ": " + b.total + " runs, " + b.failed + " failed",
            )
            .join(". ")
        }
      >
        {buckets.map((b) => (
          <div
            key={label(b.startedAt)}
            title={
              format(b) + ": " + b.total + " runs, " + b.failed + " failed"
            }
          >
            <i style={{ height: (Number(b.total) / maximum) * 100 + "%" }} />
          </div>
        ))}
      </div>
      {buckets.length > 0 && (
        <div className="axis">
          <span>{format(buckets[0])}</span>
          <span>{format(buckets[buckets.length - 1])}</span>
        </div>
      )}
      <div className="cost">
        Estimated inference cost{" "}
        <strong>
          {usage.estimatedCostUsd == null
            ? "Unpriced"
            : new Intl.NumberFormat("en-US", {
                style: "currency",
                currency: "USD",
                maximumFractionDigits: 6,
              }).format(Number(usage.estimatedCostUsd))}
        </strong>
      </div>
    </section>
  );
}
