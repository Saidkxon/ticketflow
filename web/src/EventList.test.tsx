import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EventList } from './EventList';

function renderList() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <EventList />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

function answer(status: number, body: unknown, contentType = 'application/json') {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue(
      new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } }),
    ),
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('EventList', () => {
  it('shows a loading message first', () => {
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise(() => {})));
    renderList();
    expect(screen.getByRole('status')).toHaveTextContent('Loading events');
  });

  it('shows an empty state when there are no events', async () => {
    answer(200, { items: [], page: 0, size: 20, totalItems: 0 });
    renderList();
    expect(await screen.findByText(/No events yet/)).toBeInTheDocument();
  });

  it('lists the events it receives', async () => {
    answer(200, {
      items: [
        { id: '6f1c2b0e-7d5a-4b9e-8c11-2a3b4c5d6e7f', title: 'Spring Concert', startsAt: '2026-12-01T18:00:00Z' },
        { id: '0a9b8c7d-6e5f-4a3b-9c2d-1e0f9a8b7c6d', title: 'Autumn Festival', startsAt: '2026-12-02T18:00:00Z' },
      ],
      page: 0,
      size: 20,
      totalItems: 2,
    });
    renderList();
    expect(await screen.findByText('Spring Concert')).toBeInTheDocument();
    expect(screen.getByText('Autumn Festival')).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
  });

  it('explains an unavailable service by its code and shows the reference', async () => {
    answer(
      503,
      { type: 'x', title: 'Upstream unavailable', status: 503, code: 'UPSTREAM_UNAVAILABLE', correlationId: 'abc-123' },
      'application/problem+json',
    );
    renderList();
    expect(await screen.findByRole('alert')).toHaveTextContent('not answering right now');
    expect(screen.getByText(/Reference: abc-123/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('shows a general message for any other failure', async () => {
    answer(500, { type: 'x', title: 'Internal server error', status: 500, code: 'INTERNAL_ERROR' }, 'application/problem+json');
    renderList();
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong');
  });

  describe('Near me', () => {
    const page = (items: unknown[]) => ({ items, page: 0, size: 20, totalItems: items.length });
    const concert = { id: 'e1', title: 'Spring Concert', startsAt: '2026-12-01T18:00:00Z' };
    const nearConcert = { ...concert, distanceKm: 12.4 };

    function stubFetch(handler: (url: URL) => unknown) {
      const mock = vi.fn().mockImplementation((input: string) =>
        Promise.resolve(
          new Response(JSON.stringify(handler(new URL(input, 'http://localhost'))), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        ),
      );
      vi.stubGlobal('fetch', mock);
      return mock;
    }

    function stubPosition(result: { latitude: number; longitude: number } | { code: number }) {
      const getCurrentPosition = vi.fn((ok: PositionCallback, fail: PositionErrorCallback) => {
        if ('code' in result) {
          fail({ code: result.code } as GeolocationPositionError);
        } else {
          ok({ coords: result } as GeolocationPosition);
        }
      });
      vi.stubGlobal('navigator', { geolocation: { getCurrentPosition } });
      return getCurrentPosition;
    }

    const lastUrl = (mock: ReturnType<typeof vi.fn>) => new URL(mock.mock.calls.at(-1)![0], 'http://localhost');

    it('does not ask for the position until the button is pressed', async () => {
      stubFetch(() => page([concert]));
      const getCurrentPosition = stubPosition({ latitude: 41.3, longitude: 69.2 });
      renderList();
      await screen.findByText('Spring Concert');
      expect(getCurrentPosition).not.toHaveBeenCalled();
      expect(screen.getByRole('button', { name: 'Near me' })).toBeInTheDocument();
    });

    it('searches near the visitor with the point rounded to about a kilometre and shows the distances', async () => {
      const mock = stubFetch((url) => (url.searchParams.has('nearLatitude') ? page([nearConcert]) : page([concert])));
      stubPosition({ latitude: 41.31116, longitude: 69.27976 });
      renderList();
      await screen.findByText('Spring Concert');

      fireEvent.click(screen.getByRole('button', { name: 'Near me' }));
      expect(await screen.findByText('about 12 km away')).toBeInTheDocument();
      const url = lastUrl(mock);
      expect(url.searchParams.get('nearLatitude')).toBe('41.31');
      expect(url.searchParams.get('nearLongitude')).toBe('69.28');
      expect(url.searchParams.get('radiusKm')).toBe('50');
      expect(screen.getByText(/venue location is not known are not shown/)).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Near me' })).not.toBeInTheDocument();
    });

    it('searches again with another distance', async () => {
      const mock = stubFetch(() => page([nearConcert]));
      stubPosition({ latitude: 41.3, longitude: 69.2 });
      renderList();
      fireEvent.click(await screen.findByRole('button', { name: 'Near me' }));
      await screen.findByText('about 12 km away');

      fireEvent.change(screen.getByRole('combobox'), { target: { value: '10' } });
      await waitFor(() => expect(lastUrl(mock).searchParams.get('radiusKm')).toBe('10'));
    });

    it('goes back to all events', async () => {
      const mock = stubFetch((url) => (url.searchParams.has('nearLatitude') ? page([nearConcert]) : page([concert])));
      stubPosition({ latitude: 41.3, longitude: 69.2 });
      renderList();
      fireEvent.click(await screen.findByRole('button', { name: 'Near me' }));
      await screen.findByText('about 12 km away');

      fireEvent.click(screen.getByRole('button', { name: 'Show all events' }));
      await waitFor(() => expect(screen.queryByText('about 12 km away')).not.toBeInTheDocument());
      expect(lastUrl(mock).searchParams.has('nearLatitude')).toBe(false);
      expect(screen.getByRole('button', { name: 'Near me' })).toBeInTheDocument();
    });

    it('says so when nothing is near, and how to go on', async () => {
      stubFetch((url) => (url.searchParams.has('nearLatitude') ? page([]) : page([concert])));
      stubPosition({ latitude: 41.3, longitude: 69.2 });
      renderList();
      fireEvent.click(await screen.findByRole('button', { name: 'Near me' }));
      expect(await screen.findByText(/No events within 50 km of you/)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Show all events' })).toBeInTheDocument();
    });

    it('keeps all events and explains it when the permission is refused', async () => {
      stubFetch(() => page([concert]));
      stubPosition({ code: 1 });
      renderList();
      fireEvent.click(await screen.findByRole('button', { name: 'Near me' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('Location is turned off for this site');
      expect(screen.getByText('Spring Concert')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Near me' })).toBeEnabled();
    });

    it('explains a browser without location support', async () => {
      stubFetch(() => page([concert]));
      vi.stubGlobal('navigator', {});
      renderList();
      fireEvent.click(await screen.findByRole('button', { name: 'Near me' }));
      expect(await screen.findByRole('alert')).toHaveTextContent('does not offer location on this address');
    });

    it('never saves the position anywhere', async () => {
      stubFetch(() => page([nearConcert]));
      stubPosition({ latitude: 41.3, longitude: 69.2 });
      const setItem = vi.spyOn(Storage.prototype, 'setItem');
      renderList();
      fireEvent.click(await screen.findByRole('button', { name: 'Near me' }));
      await screen.findByText('about 12 km away');
      expect(setItem).not.toHaveBeenCalled();
      setItem.mockRestore();
    });
  });
});
