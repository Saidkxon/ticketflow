import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import type { User } from '../api/auth';
import { SessionProvider } from '../auth/SessionContext';
import { createSessionStore } from '../auth/sessionStore';
import type { LatLng, MapsApi, MarkerOptions } from '../admin/maps/loadMaps';
import { ADA, answeredConsentResponse, fakeApi, token } from '../test/fakeApi';

// The real map library is never loaded in tests: the loader is replaced by a recorder.
const maps = vi.hoisted(() => ({
  loadCalls: 0,
  markers: [] as MarkerOptions[],
  clickHandlers: [] as (() => void)[],
  popups: [] as HTMLElement[],
  lines: [] as LatLng[][],
  failLoading: false,
}));

vi.mock('../admin/maps/loadMaps', () => {
  const api: MapsApi = {
    createMap: () => ({
      fitBounds: () => undefined,
      setCenter: () => undefined,
      setZoom: () => undefined,
      destroy: () => undefined,
    }),
    createBounds: () => ({ extend: () => undefined }),
    createMarker: (options) => {
      maps.markers.push(options);
      return {
        remove: () => undefined,
        onClick: (handler) => {
          maps.clickHandlers.push(handler);
        },
        openPopup: (content) => {
          maps.popups.push(content);
        },
      };
    },
    createPolyline: (_map, path) => {
      maps.lines.push(path);
      return { remove: () => undefined };
    },
  };
  return {
    loadMaps: () => {
      maps.loadCalls += 1;
      return maps.failLoading ? Promise.reject(new Error('blocked')) : Promise.resolve(api);
    },
  };
});

interface Call {
  url: string;
  method: string;
}

const TRAP = '<img src=x onerror=alert(1)>';
const LATEST = {
  items: [
    {
      userId: 'u1',
      displayName: 'Grace Hopper',
      level: 'FULL',
      latitude: 41.31,
      longitude: 69.28,
      accuracyMeters: 209.4,
      recordedAt: '2026-11-20T11:46:38Z',
    },
    {
      userId: 'u2',
      displayName: TRAP,
      level: 'MINIMUM',
      latitude: 41.32,
      longitude: 69.29,
      accuracyMeters: 15,
      recordedAt: '2026-11-20T11:00:00Z',
    },
  ],
};
const HISTORY = {
  userId: 'u1',
  displayName: 'Grace Hopper',
  level: 'FULL',
  items: [
    { latitude: 41.31, longitude: 69.28, accuracyMeters: 20, recordedAt: '2026-11-20T11:46:38Z' },
    { latitude: 41.3, longitude: 69.27, accuracyMeters: 30, recordedAt: '2026-11-20T10:46:38Z' },
    { latitude: 41.29, longitude: 69.26, accuracyMeters: 40, recordedAt: '2026-11-20T09:46:38Z' },
  ],
};

let calls: Call[];
let latestResponse: () => Response;

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

beforeEach(() => {
  calls = [];
  maps.loadCalls = 0;
  maps.markers.length = 0;
  maps.clickHandlers.length = 0;
  maps.lines.length = 0;
  maps.popups.length = 0;
  maps.failLoading = false;
  latestResponse = () => json(200, LATEST);
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation((input: string, init?: RequestInit) => {
      if (input.startsWith('/api/v1/location/')) {
        return Promise.resolve(answeredConsentResponse());
      }
      calls.push({ url: input, method: init?.method ?? 'GET' });
      if (input === '/api/v1/admin/location/latest') {
        return Promise.resolve(latestResponse());
      }
      if (input.startsWith('/api/v1/admin/location/users/u1/history')) {
        return Promise.resolve(json(200, HISTORY));
      }
      return Promise.resolve(json(404, {}));
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderAs(roles: User['roles'], path = '/admin/locations') {
  const api = fakeApi();
  api.refresh.mockResolvedValue(token('access-1'));
  api.fetchMe.mockResolvedValue({ ...ADA, roles });
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

const adminCalls = () => calls.filter((call) => call.url.startsWith('/api/v1/admin/'));

describe('who sees the page', () => {
  it('tells a customer that an administrator account is needed and reads nothing', async () => {
    renderAs(['CUSTOMER']);

    expect(await screen.findByText('This area needs an administrator account.')).toBeInTheDocument();
    expect(adminCalls()).toEqual([]);
  });

  it('offers the Locations link to an administrator only', async () => {
    renderAs(['CUSTOMER', 'ADMIN'], '/');
    expect(await screen.findByRole('link', { name: 'Locations' })).toBeInTheDocument();
  });
});

describe('the map of latest positions', () => {
  it('puts one named marker per tracked user on the map and lists them in a table', async () => {
    renderAs(['CUSTOMER', 'ADMIN']);

    expect(await screen.findByRole('cell', { name: 'Grace Hopper' })).toBeInTheDocument();
    await waitFor(() => expect(maps.markers).toHaveLength(2));
    expect(maps.loadCalls).toBe(1);
    expect(maps.markers[0]).toMatchObject({ title: 'Grace Hopper', label: 'G', position: { lat: 41.31, lng: 69.28 } });
    expect(screen.getByText('Full')).toBeInTheDocument();
    expect(screen.getByText('Minimum')).toBeInTheDocument();
    expect(screen.getByText('209 m')).toBeInTheDocument();
  });

  it('shows a name typed by a user as plain text, in the table and in the map popup', async () => {
    renderAs(['ADMIN']);
    await waitFor(() => expect(maps.clickHandlers).toHaveLength(2));

    expect(screen.getByRole('cell', { name: TRAP })).toBeInTheDocument();
    expect(document.querySelector('img')).toBeNull();
    maps.clickHandlers[1]();
    const content = maps.popups[0];
    expect(content.querySelector('img')).toBeNull();
    expect(content.querySelector('strong')?.textContent).toBe(TRAP);
    expect(content.textContent).toContain('Minimum tracking');
  });

  it('reads the data once, never by a timer, and again only when the button is pressed', async () => {
    renderAs(['ADMIN']);
    await screen.findByRole('cell', { name: 'Grace Hopper' });
    expect(adminCalls()).toHaveLength(1);

    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));

    await waitFor(() => expect(adminCalls()).toHaveLength(2));
    expect(adminCalls().every((call) => call.url === '/api/v1/admin/location/latest')).toBe(true);
  });

  it('says so when nobody shares a position', async () => {
    latestResponse = () => json(200, { items: [] });
    renderAs(['ADMIN']);

    expect(await screen.findByText('No user has shared a position yet.')).toBeInTheDocument();
    expect(maps.markers).toEqual([]);
  });

  it('explains a failure and never shows stale data', async () => {
    latestResponse = () => json(403, { code: 'FORBIDDEN' });
    renderAs(['ADMIN']);

    expect(await screen.findByRole('alert')).toHaveTextContent('not allowed to read location data');
  });
});

describe('without a working map', () => {
  it('still shows the table when the map cannot be loaded', async () => {
    maps.failLoading = true;
    renderAs(['ADMIN']);

    expect(await screen.findByRole('cell', { name: 'Grace Hopper' })).toBeInTheDocument();
    expect(await screen.findByText(/map could not be loaded/)).toBeInTheDocument();
  });
});

describe("one user's history", () => {
  it('opens the history, draws the trail oldest to newest, and goes back', async () => {
    renderAs(['ADMIN']);
    fireEvent.click(await screen.findByRole('button', { name: 'History of Grace Hopper' }));

    expect(await screen.findByRole('heading', { name: 'Location history: Grace Hopper' })).toBeInTheDocument();
    expect(calls.at(-1)?.url).toBe('/api/v1/admin/location/users/u1/history?limit=500');
    expect(await screen.findByText(/3 points/)).toBeInTheDocument();
    await waitFor(() => expect(maps.lines).toHaveLength(1));
    expect(maps.lines[0]).toEqual([
      { lat: 41.29, lng: 69.26 },
      { lat: 41.3, lng: 69.27 },
      { lat: 41.31, lng: 69.28 },
    ]);
    expect(maps.markers.filter((marker) => marker.small)).toHaveLength(3);

    fireEvent.click(screen.getByRole('button', { name: 'Back to all users' }));

    expect(await screen.findByRole('heading', { name: 'User locations' })).toBeInTheDocument();
  });
});
