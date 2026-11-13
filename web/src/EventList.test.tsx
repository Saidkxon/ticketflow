import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { EventList } from './EventList';

function renderList() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <EventList />
    </QueryClientProvider>,
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
});
