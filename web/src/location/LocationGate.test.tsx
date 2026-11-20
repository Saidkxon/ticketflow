import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import type { LocationConsent } from '../api/location';
import { SessionProvider } from '../auth/SessionContext';
import { createSessionStore } from '../auth/sessionStore';
import { fakeApi, token } from '../test/fakeApi';

interface Call {
  url: string;
  method: string;
  body: unknown;
}

const NOT_ANSWERED: LocationConsent = { currentTextVersion: 1, needsAnswer: true };
const FULL: LocationConsent = {
  level: 'FULL',
  textVersion: 1,
  decidedAt: '2026-11-20T10:00:00Z',
  currentTextVersion: 1,
  needsAnswer: false,
};

let calls: Call[];
let consent: LocationConsent;
let putAnswer: (body: { level: 'FULL' | 'MINIMUM' }) => Response;
let consentGetFails: boolean;
const originalGeolocation = Object.getOwnPropertyDescriptor(navigator, 'geolocation')!;

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

beforeEach(() => {
  calls = [];
  consent = NOT_ANSWERED;
  consentGetFails = false;
  putAnswer = (body) => {
    consent = { ...FULL, level: body.level };
    return json(200, consent);
  };
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation((input: string, init?: RequestInit) => {
      const body = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined;
      calls.push({ url: input, method: init?.method ?? 'GET', body });
      if (input === '/api/v1/location/consent' && init?.method === 'PUT') {
        return Promise.resolve(putAnswer(body));
      }
      if (input === '/api/v1/location/consent') {
        return Promise.resolve(consentGetFails ? json(503, { code: 'UPSTREAM_UNAVAILABLE' }) : json(200, consent));
      }
      if (input === '/api/v1/location/positions') {
        return Promise.resolve(new Response(null, { status: 201 }));
      }
      return Promise.resolve(json(200, { items: [], page: 0, size: 20, totalItems: 0 }));
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
  Object.defineProperty(navigator, 'geolocation', originalGeolocation);
});

function renderApp(path = '/account') {
  const api = fakeApi();
  api.refresh.mockResolvedValue(token('access-1'));
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <SessionProvider store={createSessionStore(api)}>
          <App />
        </SessionProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const puts = () => calls.filter((call) => call.method === 'PUT');
const positions = () => calls.filter((call) => call.url === '/api/v1/location/positions');

describe('the location question is mandatory', () => {
  it('replaces the page with the consent screen, which has no way to skip it', async () => {
    renderApp();

    expect(await screen.findByRole('heading', { name: 'Your location' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Allow full tracking/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Allow minimum tracking/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Not now' })).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Your account' })).not.toBeInTheDocument();
    // The text says what is collected, who sees it and for how long.
    expect(screen.getByText(/administrators can see your latest position/)).toBeInTheDocument();
    expect(screen.getByText(/kept for 30 days/)).toBeInTheDocument();
    expect(screen.getByText(/kept for 5 days/)).toBeInTheDocument();
  });

  it('lets the person in after Full and sends the text version with the answer', async () => {
    renderApp();

    fireEvent.click(await screen.findByRole('button', { name: /Allow full tracking/ }));

    expect(await screen.findByRole('heading', { name: 'Your account' })).toBeInTheDocument();
    expect(puts()).toHaveLength(1);
    expect(puts()[0].body).toEqual({ level: 'FULL', textVersion: 1 });
  });

  it('sends MINIMUM when the person chooses minimum tracking', async () => {
    renderApp();

    fireEvent.click(await screen.findByRole('button', { name: /Allow minimum tracking/ }));

    expect(await screen.findByRole('heading', { name: 'Your account' })).toBeInTheDocument();
    expect(puts()[0].body).toEqual({ level: 'MINIMUM', textVersion: 1 });
  });

  it('stays on the question when the answer cannot be saved', async () => {
    putAnswer = () => json(409, { code: 'CONSENT_TEXT_OUTDATED' });
    renderApp();

    fireEvent.click(await screen.findByRole('button', { name: /Allow full tracking/ }));

    expect(await screen.findByRole('alert')).toHaveTextContent('The consent text was updated');
    expect(screen.queryByRole('heading', { name: 'Your account' })).not.toBeInTheDocument();
  });

  it('asks again when the answer was given for an older text', async () => {
    consent = { ...FULL, textVersion: 0, needsAnswer: true };
    renderApp();

    expect(await screen.findByRole('button', { name: /Allow full tracking/ })).toBeInTheDocument();
  });

  it('does not let the person in when the settings cannot be loaded, and offers another try', async () => {
    consentGetFails = true;
    renderApp();

    expect(await screen.findByRole('alert')).toHaveTextContent('could not be loaded');
    expect(screen.queryByRole('heading', { name: 'Your account' })).not.toBeInTheDocument();
    consentGetFails = false;
    consent = FULL;
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('heading', { name: 'Your account' })).toBeInTheDocument();
  });

  it('shows "Not now" only when the build setting allows it, and then asks for nothing', async () => {
    vi.stubEnv('VITE_LOCATION_ALLOW_NOT_NOW', 'true');
    renderApp();

    fireEvent.click(await screen.findByRole('button', { name: 'Not now' }));

    expect(await screen.findByRole('heading', { name: 'Your account' })).toBeInTheDocument();
    expect(puts()).toEqual([]);
    expect(positions()).toEqual([]);
  });

  it('keeps the person out when the setting has any other value', async () => {
    vi.stubEnv('VITE_LOCATION_ALLOW_NOT_NOW', 'yes');
    renderApp();

    expect(await screen.findByRole('button', { name: /Allow full tracking/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Not now' })).not.toBeInTheDocument();
  });
});

describe('browser permission', () => {
  it('blocks the app while the browser refuses location, and lets the person in after a retry that works', async () => {
    consent = FULL;
    const getCurrentPosition = vi
      .spyOn(navigator.geolocation, 'getCurrentPosition')
      .mockImplementationOnce((_ok, error) => error?.({ code: 1 } as GeolocationPositionError));
    renderApp();

    expect(await screen.findByRole('heading', { name: 'Location access is required' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Your account' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Not now' })).not.toBeInTheDocument();

    getCurrentPosition.mockImplementation((ok) =>
      ok({ coords: { latitude: 41.3, longitude: 69.2, accuracy: 10 }, timestamp: 1_000_000 } as GeolocationPosition),
    );
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByRole('heading', { name: 'Your account' })).toBeInTheDocument();
  });

  it('blocks the app when the browser has no location support', async () => {
    consent = FULL;
    Reflect.deleteProperty(navigator, 'geolocation');
    renderApp();

    expect(await screen.findByRole('heading', { name: 'Location access is required' })).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('cannot give TicketFlow your location');
  });

  it('does not treat a slow or unavailable position as a refusal', async () => {
    consent = FULL;
    vi.spyOn(navigator.geolocation, 'getCurrentPosition').mockImplementation((_ok, error) =>
      error?.({ code: 3 } as GeolocationPositionError),
    );
    renderApp();

    expect(await screen.findByRole('heading', { name: 'Your account' })).toBeInTheDocument();
  });
});

describe('sending positions', () => {
  function startWatching() {
    let report: PositionCallback = () => undefined;
    vi.spyOn(navigator.geolocation, 'watchPosition').mockImplementation((ok) => {
      report = ok;
      return 7;
    });
    return (latitude: number, atMs: number) =>
      act(() => {
        report({ coords: { latitude, longitude: 69.2797, accuracy: 12 }, timestamp: atMs } as GeolocationPosition);
      });
  }

  it('sends the first reading, skips a near one, and sends one after 200 m and a minute', async () => {
    consent = FULL;
    const reading = startWatching();
    renderApp();
    await screen.findByRole('heading', { name: 'Your account' });

    reading(41.3111, 1_700_000_000_000);
    await waitFor(() => expect(positions()).toHaveLength(1));
    expect(positions()[0].method).toBe('POST');
    expect(positions()[0].body).toEqual({
      latitude: 41.3111,
      longitude: 69.2797,
      accuracyMeters: 12,
      recordedAt: new Date(1_700_000_000_000).toISOString(),
    });

    reading(41.3112, 1_700_000_010_000); // 11 m, 10 s later
    reading(41.3161, 1_700_000_030_000); // 556 m, but only 30 s later
    expect(positions()).toHaveLength(1);

    reading(41.3161, 1_700_000_070_000); // 556 m and 70 s later
    await waitFor(() => expect(positions()).toHaveLength(2));
  });

  it('sends nothing while the question is unanswered', async () => {
    const watch = vi.spyOn(navigator.geolocation, 'watchPosition');
    renderApp();
    await screen.findByRole('heading', { name: 'Your location' });

    expect(watch).not.toHaveBeenCalled();
    expect(positions()).toEqual([]);
  });
});

describe('the account page setting', () => {
  it('switches from Full to Minimum, warns about the deletion, and cannot switch tracking off', async () => {
    consent = FULL;
    renderApp();

    expect(await screen.findByRole('heading', { name: 'Location tracking' })).toBeInTheDocument();
    expect(screen.getAllByRole('radio')).toHaveLength(2);
    expect(screen.getByRole('radio', { name: /Full/ })).toBeChecked();
    const save = screen.getByRole('button', { name: 'Save location setting' });
    expect(save).toBeDisabled();

    fireEvent.click(screen.getByRole('radio', { name: /Minimum/ }));
    expect(screen.getByText(/deletes your position history older than 5 days/)).toBeInTheDocument();
    fireEvent.click(save);

    expect(await screen.findByText('Saved.')).toBeInTheDocument();
    expect(puts()[0].body).toEqual({ level: 'MINIMUM', textVersion: 1 });
    expect(screen.getByRole('radio', { name: /Minimum/ })).toBeChecked();
  });
});
