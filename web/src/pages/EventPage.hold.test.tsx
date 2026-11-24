import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SessionProvider } from '../auth/SessionContext';
import { createSessionStore } from '../auth/sessionStore';
import { fakeApi, token } from '../test/fakeApi';
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

const BOOKING = {
  id: 'booking-1',
  eventId: 'event-1',
  status: 'HELD',
  currency: 'UZS',
  totalMinor: 40000000,
  expiresAt: '2026-11-24T10:10:00Z',
  serverTime: '2026-11-24T10:00:00Z',
  seats: [
    { seatId: 's0-A-1', section: 'Stalls', row: 'A', number: 1, tier: 'Standard', priceMinor: 20000000 },
    { seatId: 's0-A-2', section: 'Stalls', row: 'A', number: 2, tier: 'Standard', priceMinor: 20000000 },
  ],
};

function json(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json', ...headers },
  });
}
const problem = (status: number, code: string, extra: Record<string, unknown> = {}) =>
  json(status, { type: 'about:blank', title: code, status, code, ...extra });
const page = (items: unknown[]) => json(200, { items, page: 0, size: 50, totalItems: items.length });

/** What Booking answers. Each test replaces what it needs. */
const server = {
  hold: (): Response | Promise<Response> => json(201, BOOKING),
  list: (): Response => page([]),
  release: (): Response => json(200, { ...BOOKING, status: 'CANCELLED' }),
};

let fetchMock: ReturnType<typeof vi.fn>;
const holdCalls = () => fetchMock.mock.calls.filter(([url, init]) => url === '/api/v1/bookings' && init?.method === 'POST');

beforeEach(() => {
  server.hold = () => json(201, BOOKING);
  server.list = () => page([]);
  server.release = () => json(200, { ...BOOKING, status: 'CANCELLED' });
  fetchMock = vi.fn().mockImplementation((input: string, init?: RequestInit) => {
    if (input === '/api/v1/bookings') return Promise.resolve(server.hold());
    if (input.startsWith('/api/v1/bookings?')) return Promise.resolve(server.list());
    if (input.endsWith('/release')) return Promise.resolve(server.release());
    if (input.endsWith('/availability')) {
      return Promise.resolve(json(200, { eventId: 'event-1', unavailableSeatIds: [] }, { ETag: 'W/"0"' }));
    }
    if (input.endsWith('/layout')) return Promise.resolve(json(200, layout, { ETag: '"3"' }));
    void init;
    return Promise.resolve(json(200, EVENT));
  });
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderPage(signedIn = true) {
  const api = fakeApi();
  if (signedIn) {
    api.refresh.mockResolvedValue(token('access-1'));
  }
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter initialEntries={['/events/event-1']}>
      <SessionProvider store={createSessionStore(api)}>
        <QueryClientProvider client={client}>
          <Routes>
            <Route path="events/:eventId" element={<EventPage />} />
          </Routes>
        </QueryClientProvider>
      </SessionProvider>
    </MemoryRouter>,
  );
}

const seat = (name: RegExp) => screen.getByRole('checkbox', { name });

async function ready() {
  await waitFor(() => expect(screen.getByRole('group', { name: 'Seat map' })).toHaveAttribute('aria-busy', 'false'));
}

/** Chooses two seats and waits until the person is known, so the Hold button can be used. */
async function chooseTwo() {
  await ready();
  fireEvent.click(seat(/Stalls, row A, seat 1,/));
  fireEvent.click(seat(/Stalls, row A, seat 2,/));
  const button = await screen.findByRole('button', { name: 'Hold 2 seats' });
  await waitFor(() => expect(button).toBeEnabled());
  return button;
}

describe('holding seats', () => {
  it('asks a signed-out person to sign in, and keeps the choice', async () => {
    renderPage(false);
    await ready();
    fireEvent.click(seat(/Stalls, row A, seat 1,/));
    await waitFor(() => expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login'));
    expect(screen.getByRole('link', { name: 'create an account' })).toHaveAttribute('href', '/register');
    expect(screen.queryByRole('button', { name: /^Hold/ })).not.toBeInTheDocument();
    expect(window.sessionStorage.getItem('ticketflow.selection.event-1')).not.toBeNull();
  });

  it('holds the chosen seats, shows the time left from the server clock and clears the choice', async () => {
    renderPage();
    fireEvent.click(await chooseTwo());

    const panel = await screen.findByRole('region', { name: 'Your seats are held' });
    // expiresAt - serverTime = 10 minutes, whatever the clock of this device says.
    expect(within(panel).getByTestId('countdown').textContent).toMatch(/^(10:00|9:5\d)$/);
    expect(within(panel).getAllByRole('listitem')).toHaveLength(2);
    expect(within(panel).getByTestId('total').textContent).toMatch(/^400[,.\s ]?000 UZS$/);
    expect(window.sessionStorage.getItem('ticketflow.selection.event-1')).toBeNull();
    expect(seat(/Stalls, row A, seat 1,/)).toHaveAttribute('aria-checked', 'true');

    const [url, init] = holdCalls()[0];
    expect(url).toBe('/api/v1/bookings');
    expect(JSON.parse(init.body)).toEqual({ eventId: 'event-1', seatIds: ['s0-A-1', 's0-A-2'] });
    expect(new Headers(init.headers).get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer access-1');
  });

  it('does not let the held seats be changed on the map', async () => {
    renderPage();
    fireEvent.click(await chooseTwo());
    await screen.findByRole('region', { name: 'Your seats are held' });
    fireEvent.click(seat(/Stalls, row A, seat 1,/));
    expect(seat(/Stalls, row A, seat 1,/)).toHaveAttribute('aria-checked', 'true');
    fireEvent.click(seat(/Stalls, row B, seat 1,/));
    expect(seat(/Stalls, row B, seat 1,/)).toHaveAttribute('aria-checked', 'false');
  });

  it('sends one request when the button is pressed twice', async () => {
    let answer: (response: Response) => void = () => undefined;
    server.hold = () => new Promise<Response>((resolve) => (answer = resolve));
    renderPage();
    const button = await chooseTwo();
    fireEvent.click(button);
    fireEvent.click(button);
    expect(await screen.findByRole('button', { name: 'Holding…' })).toBeDisabled();
    answer(json(201, BOOKING));
    await screen.findByRole('region', { name: 'Your seats are held' });
    expect(holdCalls()).toHaveLength(1);
  });

  it('retries the same choice with the same key, and another choice with a new key', async () => {
    server.hold = () => problem(503, 'UPSTREAM_UNAVAILABLE');
    renderPage();
    const button = await chooseTwo();
    fireEvent.click(button);
    expect(await screen.findByRole('alert')).toHaveTextContent('Trying again is safe');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Hold 2 seats' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: 'Hold 2 seats' }));
    await waitFor(() => expect(holdCalls()).toHaveLength(2));
    const keyOf = (index: number) => new Headers(holdCalls()[index][1].headers).get('Idempotency-Key');
    expect(keyOf(1)).toBe(keyOf(0));

    fireEvent.click(seat(/Stalls, row B, seat 1,/));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Hold 3 seats' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: 'Hold 3 seats' }));
    await waitFor(() => expect(holdCalls()).toHaveLength(3));
    expect(keyOf(2)).not.toBe(keyOf(0));
  });

  it('takes the refused seats out of the choice and says so', async () => {
    server.hold = () => problem(409, 'SEATS_UNAVAILABLE', { unavailableSeatIds: ['s0-A-2'] });
    renderPage();
    fireEvent.click(await chooseTwo());
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Stalls, row A, seat 2 was just taken by somebody else. It was removed from your seats.',
    );
    expect(seat(/Stalls, row A, seat 1,/)).toHaveAttribute('aria-checked', 'true');
    expect(seat(/Stalls, row A, seat 2,/)).toHaveAttribute('aria-checked', 'false');
    expect(screen.queryByRole('region', { name: 'Your seats are held' })).not.toBeInTheDocument();
  });

  it('shows the hold that already exists when the server says there is one', async () => {
    server.hold = () => problem(409, 'ACTIVE_HOLD_EXISTS');
    renderPage();
    const button = await chooseTwo();
    server.list = () => page([BOOKING]);
    fireEvent.click(button);
    expect(await screen.findByRole('region', { name: 'Your seats are held' })).toBeInTheDocument();
    expect(screen.getByText(/You already hold seats for this event/)).toBeInTheDocument();
  });

  it('explains a busy answer and a rate limit', async () => {
    server.hold = () => problem(409, 'SEATS_BUSY');
    renderPage();
    const button = await chooseTwo();
    fireEvent.click(button);
    expect(await screen.findByRole('alert')).toHaveTextContent('Many people are choosing the same seats');

    server.hold = () => {
      const response = problem(429, 'RATE_LIMITED');
      return new Response(response.body, { status: 429, headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '12' } });
    };
    await waitFor(() => expect(screen.getByRole('button', { name: 'Hold 2 seats' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: 'Hold 2 seats' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('wait 12 seconds'));
  });

  it('shows a hold that is still running after the page is loaded again', async () => {
    server.list = () => page([{ ...BOOKING, id: 'old', status: 'EXPIRED' }, BOOKING, { ...BOOKING, id: 'x', eventId: 'event-2' }]);
    renderPage();
    const panel = await screen.findByRole('region', { name: 'Your seats are held' });
    expect(within(panel).getAllByRole('listitem')).toHaveLength(2);
    expect(screen.queryByRole('button', { name: /^Hold/ })).not.toBeInTheDocument();
  });

  it('gives the seats back when asked', async () => {
    renderPage();
    fireEvent.click(await chooseTwo());
    const panel = await screen.findByRole('region', { name: 'Your seats are held' });
    fireEvent.click(within(panel).getByRole('button', { name: 'Release seats' }));

    expect(await screen.findByText('Your seats were released.')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Your seats are held' })).not.toBeInTheDocument();
    const release = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/release'));
    expect(release?.[0]).toBe('/api/v1/bookings/booking-1/release');
    expect(release?.[1].method).toBe('POST');
    expect(seat(/Stalls, row A, seat 1,/)).toHaveAttribute('aria-checked', 'false');
  });

  it('keeps the hold on screen when releasing fails, and accepts a hold that had ended', async () => {
    server.release = () => problem(503, 'UPSTREAM_UNAVAILABLE');
    renderPage();
    fireEvent.click(await chooseTwo());
    fireEvent.click(await screen.findByRole('button', { name: 'Release seats' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('could not be released');
    expect(screen.getByRole('region', { name: 'Your seats are held' })).toBeInTheDocument();

    server.release = () => problem(409, 'BOOKING_NOT_ACTIVE');
    fireEvent.click(screen.getByRole('button', { name: 'Release seats' }));
    expect(await screen.findByText('This hold had already ended.')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Your seats are held' })).not.toBeInTheDocument();
  });
});
