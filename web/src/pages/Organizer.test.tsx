import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import type { User } from '../api/auth';
import type { EventDetail } from '../api/organizer';
import { SessionProvider } from '../auth/SessionContext';
import { createSessionStore } from '../auth/sessionStore';
import { ADA, answeredConsentResponse, fakeApi, token } from '../test/fakeApi';

interface Call {
  url: string;
  method: string;
  headers: Headers;
  body: unknown;
}

let calls: Call[];
let handler: (call: Call) => Response;

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function problem(status: number, code: string, errors?: { field: string; problem: string }[]): Response {
  return json(status, { status, code, title: code, errors });
}

const NO_LAYOUT = { currency: null, priceTiers: [], sections: [], totalSeats: 0 };

/** The event page also reads the event's layout; this answers it with an empty one. */
function eventAndEmptyLayout(event: EventDetail, call: Call): Response {
  return json(200, call.url.endsWith('/layout') ? NO_LAYOUT : event);
}

const DRAFT: EventDetail = {
  id: 'e1',
  title: 'Jazz Night',
  startsAt: '2099-01-10T18:00:00Z',
  venueName: 'Main Hall',
  city: 'Tashkent',
  venueLatitude: 41.3111,
  venueLongitude: 69.2797,
  status: 'DRAFT',
  version: 3,
};

beforeEach(() => {
  calls = [];
  handler = () => json(404, {});
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation((input: string, init?: RequestInit) => {
      if (input.startsWith('/api/v1/location/')) {
        return Promise.resolve(answeredConsentResponse()); // the location gate; not part of what these tests record
      }
      const call: Call = {
        url: input,
        method: init?.method ?? 'GET',
        headers: new Headers(init?.headers),
        body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
      };
      calls.push(call);
      return Promise.resolve(handler(call));
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderAs(roles: User['roles'], path: string) {
  const api = fakeApi();
  api.refresh.mockResolvedValue(token('access-1'));
  api.fetchMe.mockResolvedValue({ ...ADA, roles });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <SessionProvider store={createSessionStore(api)}>
          <App />
        </SessionProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function type(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

function fillNewEvent() {
  type('Title', '  Jazz Night ');
  type('Starts at', '2099-01-10T18:00');
  type('Venue name', 'Main Hall');
  type('City', 'Tashkent');
}

describe('organizer area access', () => {
  it('tells a customer that an organizer account is needed and asks the server for nothing', async () => {
    renderAs(['CUSTOMER'], '/organizer');

    expect(await screen.findByText(/needs an organizer account/)).toBeInTheDocument();
    expect(calls.filter((call) => call.url.startsWith('/api/v1/events'))).toEqual([]);
  });

  it('sends a signed-out visitor to the sign-in page', async () => {
    const api = fakeApi();
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter initialEntries={['/organizer']}>
          <SessionProvider store={createSessionStore(api)}>
            <App />
          </SessionProvider>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('shows organizers a link to their events in the header', async () => {
    handler = () => json(200, { items: [], page: 0, size: 20, totalItems: 0 });
    renderAs(['CUSTOMER', 'ORGANIZER'], '/');

    expect(await screen.findByRole('link', { name: 'My events' })).toHaveAttribute('href', '/organizer');
  });
});

describe('my events', () => {
  it('lists the organizer\'s events with their status and a link to each', async () => {
    handler = () =>
      json(200, {
        items: [DRAFT, { ...DRAFT, id: 'e2', title: 'Rock Festival', status: 'PUBLISHED', version: 5 }],
        page: 0,
        size: 20,
        totalItems: 2,
      });
    renderAs(['ORGANIZER'], '/organizer');

    expect(await screen.findByRole('link', { name: 'Jazz Night' })).toHaveAttribute('href', '/organizer/events/e1');
    expect(screen.getByRole('link', { name: 'Rock Festival' })).toHaveAttribute('href', '/organizer/events/e2');
    expect(screen.getByText('Draft')).toBeInTheDocument();
    expect(screen.getByText('Published')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'New event' })).toHaveAttribute('href', '/organizer/events/new');
    const request = calls.find((call) => call.url.startsWith('/api/v1/events/mine'));
    expect(request?.url).toBe('/api/v1/events/mine?page=0&size=20');
    expect(request?.headers.get('Authorization')).toBe('Bearer access-1');
  });

  it('says so when there are no events yet', async () => {
    handler = () => json(200, { items: [], page: 0, size: 20, totalItems: 0 });
    renderAs(['ORGANIZER'], '/organizer');

    expect(await screen.findByText(/no events yet/)).toBeInTheDocument();
  });

  it('shows an error with a way to retry', async () => {
    handler = () => problem(503, 'UPSTREAM_UNAVAILABLE');
    renderAs(['ORGANIZER'], '/organizer');

    expect(await screen.findByRole('alert')).toHaveTextContent(/not answering/);
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });
});

describe('creating an event', () => {
  it('checks the form before it sends anything', async () => {
    renderAs(['ORGANIZER'], '/organizer/events/new');

    fireEvent.click(await screen.findByRole('button', { name: 'Create draft' }));

    expect(await screen.findByText('Title is required.')).toBeInTheDocument();
    expect(screen.getByText('Choose the date and time the event starts.')).toBeInTheDocument();
    expect(calls.filter((call) => call.method === 'POST')).toEqual([]);
  });

  it('sends the trimmed event with the token and opens it for editing', async () => {
    handler = (call) => {
      if (call.method === 'POST') {
        return json(201, { ...DRAFT, version: 0, venueLatitude: null, venueLongitude: null });
      }
      return eventAndEmptyLayout({ ...DRAFT, version: 0 }, call);
    };
    renderAs(['ORGANIZER'], '/organizer/events/new');

    await screen.findByRole('heading', { name: 'New event' });
    fillNewEvent();
    fireEvent.click(screen.getByRole('button', { name: 'Create draft' }));

    expect(await screen.findByRole('heading', { name: 'Edit event' })).toBeInTheDocument();
    const post = calls.find((call) => call.method === 'POST');
    expect(post?.url).toBe('/api/v1/events');
    expect(post?.headers.get('Authorization')).toBe('Bearer access-1');
    expect(post?.body).toEqual({
      title: 'Jazz Night',
      startsAt: new Date('2099-01-10T18:00').toISOString().replace('.000Z', 'Z'),
      venueName: 'Main Hall',
      city: 'Tashkent',
      venueLatitude: null,
      venueLongitude: null,
    });
  });

  it('sends the venue coordinates as numbers', async () => {
    handler = (call) => (call.method === 'POST' ? json(201, DRAFT) : eventAndEmptyLayout(DRAFT, call));
    renderAs(['ORGANIZER'], '/organizer/events/new');

    await screen.findByRole('heading', { name: 'New event' });
    fillNewEvent();
    type('Latitude (optional)', '41.3111');
    type('Longitude (optional)', '69.2797');
    fireEvent.click(screen.getByRole('button', { name: 'Create draft' }));

    await screen.findByRole('heading', { name: 'Edit event' });
    expect(calls.find((call) => call.method === 'POST')?.body).toMatchObject({
      venueLatitude: 41.3111,
      venueLongitude: 69.2797,
    });
  });

  it('shows the server\'s problem next to the field it names', async () => {
    handler = () => problem(400, 'INVALID_REQUEST', [{ field: 'city', problem: 'must not contain control characters' }]);
    renderAs(['ORGANIZER'], '/organizer/events/new');

    await screen.findByRole('heading', { name: 'New event' });
    fillNewEvent();
    fireEvent.click(screen.getByRole('button', { name: 'Create draft' }));

    expect(await screen.findByText('must not contain control characters')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Please check the fields below.');
    expect(screen.getByLabelText('City')).toHaveAttribute('aria-invalid', 'true');
  });
});

describe('editing an event', () => {
  it('loads the event into the form and saves with the version it loaded', async () => {
    handler = (call) =>
      call.method === 'PUT' ? json(200, { ...DRAFT, title: 'Jazz Night 2', version: 4 }) : eventAndEmptyLayout(DRAFT, call);
    renderAs(['ORGANIZER'], '/organizer/events/e1');

    expect(await screen.findByLabelText('Title')).toHaveValue('Jazz Night');
    expect(screen.getByLabelText('Venue name')).toHaveValue('Main Hall');
    expect(screen.getByLabelText('Latitude (optional)')).toHaveValue('41.3111');
    type('Title', 'Jazz Night 2');
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByText('Saved.')).toBeInTheDocument();
    const put = calls.find((call) => call.method === 'PUT');
    expect(put?.url).toBe('/api/v1/events/e1');
    expect(put?.headers.get('If-Match')).toBe('"3"');
    expect(put?.body).toMatchObject({ title: 'Jazz Night 2' });
  });

  it('explains a change made elsewhere and offers to reload', async () => {
    handler = (call) => (call.method === 'PUT' ? problem(412, 'PRECONDITION_FAILED') : eventAndEmptyLayout(DRAFT, call));
    renderAs(['ORGANIZER'], '/organizer/events/e1');

    await screen.findByLabelText('Title');
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/Someone else changed this event/);
    expect(screen.getByRole('button', { name: 'Reload the event' })).toBeInTheDocument();
  });

  it('shows a published event read-only', async () => {
    handler = (call) => eventAndEmptyLayout({ ...DRAFT, status: 'PUBLISHED', version: 5 }, call);
    renderAs(['ORGANIZER'], '/organizer/events/e1');

    expect(await screen.findByLabelText('Title')).toBeDisabled();
    expect(screen.getByText(/This event is published/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument();
  });

  it('says so when the event is not found, with no retry button', async () => {
    handler = () => problem(404, 'NOT_FOUND');
    renderAs(['ORGANIZER'], '/organizer/events/missing');

    expect(await screen.findByRole('alert')).toHaveTextContent('This event was not found.');
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('link', { name: 'Back to my events' })).toBeInTheDocument());
  });
});
