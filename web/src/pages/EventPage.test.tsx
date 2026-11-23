import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { makeLayout } from '../test/layouts';
import { EventPage } from './EventPage';

const EVENT = {
  id: 'event-1',
  title: 'Spring Concert',
  startsAt: '2026-12-01T18:00:00Z',
  venueName: 'Main Hall',
  city: 'Tashkent',
  venueLatitude: null,
  venueLongitude: null,
  distanceKm: null,
};

const layout = makeLayout([{ name: 'Stalls', rows: 2, seatsPerRow: 6 }]);

function json(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json', ...headers },
  });
}

/** What the server answers. A test changes `availability` to make the next poll see something else. */
const server = {
  event: () => json(200, EVENT),
  layout: () => json(200, layout, { ETag: '"3"' }),
  availability: (): Response => json(200, { eventId: 'event-1', unavailableSeatIds: [] }, { ETag: 'W/"0"' }),
};

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  server.event = () => json(200, EVENT);
  server.layout = () => json(200, layout, { ETag: '"3"' });
  server.availability = () => json(200, { eventId: 'event-1', unavailableSeatIds: [] }, { ETag: 'W/"0"' });
  fetchMock = vi.fn().mockImplementation((input: string) => {
    if (input.endsWith('/availability')) return Promise.resolve(server.availability());
    if (input.endsWith('/layout')) return Promise.resolve(server.layout());
    return Promise.resolve(server.event());
  });
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderPage(path = '/events/event-1') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter initialEntries={[path]}>
      <QueryClientProvider client={client}>
        <Routes>
          <Route path="events/:eventId" element={<EventPage />} />
        </Routes>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

const availabilityCalls = () => fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/availability')).length;
const seat = (name: RegExp) => screen.getByRole('checkbox', { name });

/** Waits until the map is drawn and the first availability answer has arrived, so seats can be chosen. */
async function ready() {
  await waitFor(() => expect(screen.getByRole('group', { name: 'Seat map' })).toHaveAttribute('aria-busy', 'false'));
}

/** Asks for availability now, the way a tab that becomes visible again does. */
async function pollNow() {
  await act(async () => {
    document.dispatchEvent(new Event('visibilitychange'));
  });
}

describe('EventPage', () => {
  it('shows the event, its seat map and the choice of seats', async () => {
    renderPage();
    expect(screen.getByRole('status')).toHaveTextContent('Loading event');
    expect(await screen.findByRole('heading', { name: 'Spring Concert' })).toBeInTheDocument();
    expect(screen.getByText(/Main Hall, Tashkent/)).toBeInTheDocument();
    expect(await screen.findAllByRole('checkbox')).toHaveLength(12);
    expect(screen.getByText(/Choose up to 8 seats/)).toBeInTheDocument();
  });

  it('does not let seats be chosen before it knows which are free', async () => {
    server.availability = () => new Promise<Response>(() => {}) as unknown as Response;
    renderPage();
    expect(await screen.findByText(/Checking which seats are free/)).toBeInTheDocument();
    fireEvent.click(await screen.findByRole('checkbox', { name: /Stalls, row A, seat 1/ }));
    expect(screen.getByText(/Choose up to 8 seats/)).toBeInTheDocument();
  });

  it('adds up the price of the chosen seats and lets one be removed', async () => {
    renderPage();
    await ready();

    fireEvent.click(seat(/Stalls, row A, seat 1/));
    fireEvent.click(seat(/Stalls, row B, seat 4/));
    const summary = screen.getByRole('region', { name: 'Your seats' });
    expect(within(summary).getAllByRole('listitem')).toHaveLength(2);
    expect(within(summary).getByTestId('total').textContent).toMatch(/^400[,.\s ]?000 UZS$/);

    fireEvent.click(within(summary).getByRole('button', { name: /Remove Stalls, row A, seat 1/ }));
    expect(within(summary).getAllByRole('listitem')).toHaveLength(1);
    expect(within(summary).getByTestId('total').textContent).toMatch(/^200[,.\s ]?000 UZS$/);
    expect(seat(/Stalls, row A, seat 1/)).toHaveAttribute('aria-checked', 'false');
  });

  it('refuses a ninth seat and says why', async () => {
    const big = makeLayout([{ name: 'Stalls', rows: 2, seatsPerRow: 6 }]);
    server.layout = () => json(200, big);
    renderPage();
    await ready();
    for (const row of ['A', 'B']) {
      for (let number = 1; number <= 6; number++) {
        if (screen.queryByRole('region', { name: 'Your seats' })?.querySelectorAll('li').length === 8) break;
        fireEvent.click(seat(new RegExp(`Stalls, row ${row}, seat ${number},`)));
      }
    }
    expect(within(screen.getByRole('region', { name: 'Your seats' })).getAllByRole('listitem')).toHaveLength(8);
    fireEvent.click(seat(/Stalls, row B, seat 6,/));
    expect(screen.getByRole('alert')).toHaveTextContent('You can choose up to 8 seats at a time.');
    expect(within(screen.getByRole('region', { name: 'Your seats' })).getAllByRole('listitem')).toHaveLength(8);
  });

  it('does not let a seat that is not available be chosen', async () => {
    server.availability = () => json(200, { eventId: 'event-1', unavailableSeatIds: ['s0-A-2'] }, { ETag: 'W/"1"' });
    renderPage();
    await waitFor(() => expect(seat(/Stalls, row A, seat 2/)).toHaveAccessibleName(/not available$/));
    fireEvent.click(seat(/Stalls, row A, seat 2/));
    expect(screen.getByText(/Choose up to 8 seats/)).toBeInTheDocument();
  });

  it('takes a seat out of the choice when somebody else gets it, and says so', async () => {
    renderPage();
    await ready();
    fireEvent.click(seat(/Stalls, row A, seat 1/));
    fireEvent.click(seat(/Stalls, row A, seat 2/));

    server.availability = () => json(200, { eventId: 'event-1', unavailableSeatIds: ['s0-A-2'] }, { ETag: 'W/"2"' });
    await pollNow();

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Stalls, row A, seat 2 was just taken by somebody else. It was removed from your seats.',
    );
    const summary = screen.getByRole('region', { name: 'Your seats' });
    expect(within(summary).getAllByRole('listitem')).toHaveLength(1);
    expect(seat(/Stalls, row A, seat 1/)).toHaveAttribute('aria-checked', 'true');
    expect(seat(/Stalls, row A, seat 2/)).toHaveAccessibleName(/not available$/);
  });

  it('keeps the chosen seats over a refresh of the page', async () => {
    const first = renderPage();
    await ready();
    fireEvent.click(seat(/Stalls, row A, seat 1,/));
    fireEvent.click(seat(/Stalls, row B, seat 3,/));
    first.unmount();

    renderPage();
    await ready();
    expect(seat(/Stalls, row A, seat 1,/)).toHaveAttribute('aria-checked', 'true');
    expect(seat(/Stalls, row B, seat 3,/)).toHaveAttribute('aria-checked', 'true');
    expect(within(screen.getByRole('region', { name: 'Your seats' })).getAllByRole('listitem')).toHaveLength(2);
  });

  it('forgets the choice when every seat is removed, and keeps each event apart', async () => {
    const first = renderPage();
    await ready();
    fireEvent.click(seat(/Stalls, row A, seat 1,/));
    fireEvent.click(seat(/Stalls, row A, seat 1,/));
    first.unmount();
    expect(window.sessionStorage.getItem('ticketflow.selection.event-1')).toBeNull();

    window.sessionStorage.setItem('ticketflow.selection.other-event', JSON.stringify(['s0-A-2']));
    renderPage();
    await ready();
    expect(screen.getByText(/Choose up to 8 seats/)).toBeInTheDocument();
  });

  it('drops a remembered seat that is taken now, with a message, and one that is not in the layout', async () => {
    window.sessionStorage.setItem(
      'ticketflow.selection.event-1',
      JSON.stringify(['s0-A-1', 's0-A-2', 'no-such-seat']),
    );
    server.availability = () => json(200, { eventId: 'event-1', unavailableSeatIds: ['s0-A-2'] }, { ETag: 'W/"1"' });
    renderPage();
    await ready();
    expect(await screen.findByRole('alert')).toHaveTextContent('Stalls, row A, seat 2 was just taken');
    expect(seat(/Stalls, row A, seat 1,/)).toHaveAttribute('aria-checked', 'true');
    expect(within(screen.getByRole('region', { name: 'Your seats' })).getAllByRole('listitem')).toHaveLength(1);
  });

  it('works when the browser keeps no page state at all', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    renderPage();
    await ready();
    fireEvent.click(seat(/Stalls, row A, seat 1,/));
    expect(seat(/Stalls, row A, seat 1,/)).toHaveAttribute('aria-checked', 'true');
    vi.restoreAllMocks();
  });

  it('says that the seats are not open yet when Booking does not know the event', async () => {
    server.availability = () => json(404, { code: 'EVENT_NOT_ON_SALE' });
    renderPage();
    await screen.findByRole('heading', { name: 'Spring Concert' });
    await pollNow();
    await pollNow();
    expect(await screen.findByText(/Seats for this event are not open for booking yet/)).toBeInTheDocument();
  });

  it('tells the person when the seat map may be out of date, and when it is current again', async () => {
    renderPage();
    await ready();

    server.availability = () => json(503, { code: 'UPSTREAM_UNAVAILABLE' });
    await pollNow();
    await pollNow();
    expect(await screen.findByText(/The seat map may be out of date/)).toBeInTheDocument();

    server.availability = () => json(200, { eventId: 'event-1', unavailableSeatIds: [] }, { ETag: 'W/"0"' });
    await pollNow();
    await waitFor(() => expect(screen.queryByText(/may be out of date/)).not.toBeInTheDocument());
  });

  it('stops asking for availability when the page is left', async () => {
    const view = renderPage();
    await ready();
    const before = availabilityCalls();
    view.unmount();
    await pollNow();
    expect(availabilityCalls()).toBe(before);
  });

  it('says so when the event does not exist or is not on sale', async () => {
    server.event = () => json(404, { code: 'NOT_FOUND' });
    server.layout = () => json(404, { code: 'NOT_FOUND' });
    renderPage();
    expect(await screen.findByRole('heading', { name: 'Event not found' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to the events' })).toHaveAttribute('href', '/');
    // No seat map and no polling for an event that is not there.
    expect(availabilityCalls()).toBe(0);
  });

  it('offers to try again after an error', async () => {
    server.event = () => json(503, { code: 'UPSTREAM_UNAVAILABLE', correlationId: 'corr-1' });
    renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent('The event service is not answering right now');
    expect(screen.getByText(/Reference: corr-1/)).toBeInTheDocument();

    server.event = () => json(200, EVENT);
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('heading', { name: 'Spring Concert' })).toBeInTheDocument();
  });
});
