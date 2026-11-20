/**
 * The consent text (ADR-0015, L8). When the words change in a way that matters, raise CONSENT_TEXT_VERSION here and
 * ticketflow.identity.location.consent-text-version in Identity (application.yaml) together: everybody is then asked again.
 */
export const CONSENT_TEXT_VERSION = 1;

export const FULL_DAYS = 30;
export const MINIMUM_DAYS = 5;

export function ConsentText() {
  return (
    <>
      <p>TicketFlow shows you events near you and keeps a history of where you use the app. Here is exactly what that means:</p>
      <ul>
        <li>
          <strong>What we collect:</strong> your position (latitude, longitude and how accurate it is) and the time it was
          read. Nothing else is collected about your location.
        </li>
        <li>
          <strong>When:</strong> only while TicketFlow is open in your browser, and only when your browser allows it. It
          does not track you when the app is closed.
        </li>
        <li>
          <strong>Who can see it:</strong> TicketFlow administrators can see your latest position and your history,
          together with your name, for data analysis. Every time an administrator reads location data, it is written to a log.
        </li>
        <li>
          <strong>Full:</strong> your position history is kept for {FULL_DAYS} days, then deleted.
        </li>
        <li>
          <strong>Minimum:</strong> your position history is kept for {MINIMUM_DAYS} days, then deleted.
        </li>
        <li>You can change between Full and Minimum later on your account page. Switching to Minimum deletes older points at once.</li>
      </ul>
    </>
  );
}
