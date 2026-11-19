import { messageForOrganizerError } from './messages';
import { minorToMajor } from './layoutForm';
import { useMyLayout } from './useMyLayout';

/** The frozen layout of a published event, read-only. */
export function LayoutSummary({ eventId }: { eventId: string }) {
  const { data, error, isPending } = useMyLayout(eventId);
  return (
    <section aria-labelledby="layout-heading">
      <h2 id="layout-heading">Seating layout</h2>
      {isPending && <p role="status">Loading the layout…</p>}
      {error && <p role="alert">{messageForOrganizerError(error)}</p>}
      {data && (
        <>
          <p>
            {data.totalSeats} seats, prices in {data.currency}. The layout and prices are frozen.
          </p>
          <h3>Price tiers</h3>
          <ul>
            {data.priceTiers.map((tier) => (
              <li key={tier.id}>
                {tier.name}: {minorToMajor(tier.priceMinor)} {data.currency}
              </li>
            ))}
          </ul>
          <h3>Sections</h3>
          <ul>
            {data.sections.map((section) => (
              <li key={section.id}>
                {section.name} ({section.tier}): {section.rows} rows × {section.seatsPerRow} seats = {section.seatCount}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
