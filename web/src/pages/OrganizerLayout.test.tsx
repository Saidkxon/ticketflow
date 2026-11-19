import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../App';
import type { User } from '../api/auth';
import type { EventDetail, LayoutDetail } from '../api/organizer';
import { SessionProvider } from '../auth/SessionContext';
import { createSessionStore } from '../auth/sessionStore';
import { ADA, fakeApi, token } from '../test/fakeApi';

interface Call {
  url: string;
  method: string;
  headers: Headers;
  body: unknown;
}

let calls: Call[];
let handler: (call: Call) => Response;

function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } });
}

function problem(status: number, code: string, errors?: { field: string; problem: string }[]): Response {
  return json(status, { status, code, title: code, errors });
}

const DRAFT: EventDetail = {
  id: 'e1',
  title: 'Jazz Night',
  startsAt: '2099-01-10T18:00:00Z',
  venueName: 'Main Hall',
  city: 'Tashkent',
  venueLatitude: null,
  venueLongitude: null,
  status: 'DRAFT',
  version: 3,
};

const NO_LAYOUT: LayoutDetail = { currency: null, priceTiers: [], sections: [], totalSeats: 0 };

const SAVED_LAYOUT: LayoutDetail = {
  currency: 'UZS',
  totalSeats: 22,
  priceTiers: [
    { id: 't1', name: 'Standard', priceMinor: 15000000 },
    { id: 't2', name: 'VIP', priceMinor: 40000000 },
  ],
  sections: [
    { id: 's1', name: 'Stalls', tier: 'Standard', rows: 3, seatsPerRow: 4, seatCount: 12 },
    { id: 's2', name: 'Front', tier: 'VIP', rows: 2, seatsPerRow: 5, seatCount: 10 },
  ],
};

/** A stand-in for Catalog: one event and its layout, which saving and publishing change. */
function catalog(initial: { event?: EventDetail; layout?: LayoutDetail } = {}) {
  const state = { event: initial.event ?? DRAFT, layout: initial.layout ?? NO_LAYOUT };
  handler = (call) => {
    if (call.method === 'GET' && call.url === '/api/v1/events/mine/e1') {
      return json(200, state.event, { ETag: `"${state.event.version}"` });
    }
    if (call.method === 'GET' && call.url === '/api/v1/events/mine/e1/layout') {
      return json(200, state.layout, { ETag: `"${state.event.version}"` });
    }
    if (call.method === 'PUT' && call.url === '/api/v1/events/e1/layout') {
      state.event = { ...state.event, version: state.event.version + 1 };
      state.layout = { ...SAVED_LAYOUT };
      return json(200, state.layout, { ETag: `"${state.event.version}"` });
    }
    if (call.method === 'POST' && call.url === '/api/v1/events/e1/publish') {
      state.event = { ...state.event, status: 'PUBLISHED', version: state.event.version + 1 };
      return json(200, state.event, { ETag: `"${state.event.version}"` });
    }
    return json(404, {});
  };
  return state;
}

beforeEach(() => {
  calls = [];
  handler = () => json(404, {});
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation((input: string, init?: RequestInit) => {
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

function renderEvent(roles: User['roles'] = ['ORGANIZER']) {
  const api = fakeApi();
  api.refresh.mockResolvedValue(token('access-1'));
  api.fetchMe.mockResolvedValue({ ...ADA, roles });
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/organizer/events/e1']}>
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

/** The publish button is enabled once the layout has been read. */
async function clickPublish() {
  const button = await screen.findByRole('button', { name: 'Publish event' });
  await waitFor(() => expect(button).toBeEnabled());
  fireEvent.click(button);
}

function fillStarter() {
  type('Tier 1 price', '150000');
}

describe('the layout editor of a draft', () => {
  it('starts from a starter layout when none was saved', async () => {
    catalog();
    renderEvent();

    expect(await screen.findByLabelText('Tier 1 name')).toHaveValue('Standard');
    expect(screen.getByLabelText('Section 1 name')).toHaveValue('Main hall');
    expect(screen.getByLabelText('Section 1 rows')).toHaveValue('10');
    expect(screen.getByText(/Total: 200 seats/)).toBeInTheDocument();
  });

  it('shows a saved layout with prices in whole currency units', async () => {
    catalog({ layout: SAVED_LAYOUT });
    renderEvent();

    expect(await screen.findByLabelText('Tier 2 name')).toHaveValue('VIP');
    expect(screen.getByLabelText('Tier 1 price')).toHaveValue('150000');
    expect(screen.getByLabelText('Tier 2 price')).toHaveValue('400000');
    expect(screen.getByLabelText('Section 2 tier')).toHaveValue('VIP');
    expect(screen.getByText(/Total: 22 seats/)).toBeInTheDocument();
  });

  it('checks the layout before it sends anything', async () => {
    catalog();
    renderEvent();

    await screen.findByLabelText('Tier 1 name');
    fireEvent.click(screen.getByRole('button', { name: 'Save layout' }));

    expect(await screen.findByText(/Enter a price above zero/)).toBeInTheDocument();
    expect(calls.filter((call) => call.method === 'PUT')).toEqual([]);
  });

  it('saves with the event version, in minor units, and reports the seats', async () => {
    catalog();
    renderEvent();

    await screen.findByLabelText('Tier 1 name');
    fillStarter();
    fireEvent.click(screen.getByRole('button', { name: 'Save layout' }));

    expect(await screen.findByText('Layout saved. 22 seats.')).toBeInTheDocument();
    const put = calls.find((call) => call.method === 'PUT' && call.url.endsWith('/layout'));
    expect(put?.headers.get('If-Match')).toBe('"3"');
    expect(put?.headers.get('Authorization')).toBe('Bearer access-1');
    expect(put?.body).toEqual({
      currency: 'UZS',
      priceTiers: [{ name: 'Standard', priceMinor: 15000000 }],
      sections: [{ name: 'Main hall', tier: 'Standard', rows: 10, seatsPerRow: 20 }],
    });
  });

  it('lets sections follow a renamed tier and adds and removes tiers and sections', async () => {
    catalog();
    renderEvent();

    await screen.findByLabelText('Tier 1 name');
    type('Tier 1 name', 'Regular');
    expect(screen.getByLabelText('Section 1 tier')).toHaveValue('Regular');
    fireEvent.click(screen.getByRole('button', { name: 'Add tier' }));
    expect(screen.getByLabelText('Tier 2 name')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Add section' }));
    expect(screen.getByLabelText('Section 2 name')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Remove section 2' }));
    expect(screen.queryByLabelText('Section 2 name')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Remove tier 2' }));
    expect(screen.queryByLabelText('Tier 2 name')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove tier 1' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Remove section 1' })).toBeDisabled();
  });

  it('puts the server\'s problems on the tier or section they name', async () => {
    catalog();
    handler = (call) =>
      call.method === 'PUT'
        ? problem(400, 'INVALID_REQUEST', [{ field: 'sections[0].seatsPerRow', problem: 'must be between 1 and 100' }])
        : json(200, call.url.endsWith('/layout') ? NO_LAYOUT : DRAFT);
    renderEvent();

    await screen.findByLabelText('Tier 1 name');
    fillStarter();
    fireEvent.click(screen.getByRole('button', { name: 'Save layout' }));

    expect(await screen.findByText('must be between 1 and 100')).toBeInTheDocument();
    expect(screen.getByLabelText('Section 1 seats per row')).toHaveAttribute('aria-invalid', 'true');
  });

  it('explains a change made elsewhere and offers to reload', async () => {
    catalog();
    handler = (call) =>
      call.method === 'PUT' ? problem(412, 'PRECONDITION_FAILED') : json(200, call.url.endsWith('/layout') ? NO_LAYOUT : DRAFT);
    renderEvent();

    await screen.findByLabelText('Tier 1 name');
    fillStarter();
    fireEvent.click(screen.getByRole('button', { name: 'Save layout' }));

    expect(await screen.findByText(/Someone else changed this event/)).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Reload the event' }).length).toBeGreaterThan(0);
  });
});

describe('publishing', () => {
  it('is not possible before a layout is saved', async () => {
    catalog();
    renderEvent();

    expect(await screen.findByText(/save a layout first/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Publish event' })).toBeDisabled();
  });

  it('is not possible once the start time has passed', async () => {
    catalog({ event: { ...DRAFT, startsAt: '2020-01-01T10:00:00Z' }, layout: SAVED_LAYOUT });
    renderEvent();

    expect(await screen.findByText(/start time has passed/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Publish event' })).toBeDisabled();
  });

  it('asks for confirmation, and cancelling sends nothing', async () => {
    catalog({ layout: SAVED_LAYOUT });
    renderEvent();

    await clickPublish();
    const confirm = screen.getByRole('group', { name: 'Confirm publishing' });
    expect(confirm).toHaveTextContent(/cannot be undone/);
    fireEvent.click(within(confirm).getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('group', { name: 'Confirm publishing' })).not.toBeInTheDocument();
    expect(calls.filter((call) => call.method === 'POST')).toEqual([]);
  });

  it('publishes with the event version and then shows the event read-only with its frozen layout', async () => {
    catalog({ layout: SAVED_LAYOUT });
    renderEvent();

    await clickPublish();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, publish' }));

    await waitFor(() => expect(screen.getByLabelText('Title')).toBeDisabled());
    const post = calls.find((call) => call.method === 'POST');
    expect(post?.url).toBe('/api/v1/events/e1/publish');
    expect(post?.headers.get('If-Match')).toBe('"3"');
    expect(post?.headers.get('Authorization')).toBe('Bearer access-1');
    expect(screen.getByText(/frozen/)).toBeInTheDocument();
    expect(screen.getByText('Standard: 150000 UZS')).toBeInTheDocument();
    expect(screen.getByText('Stalls (Standard): 3 rows × 4 seats = 12')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save layout' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Publish event' })).not.toBeInTheDocument();
  });

  it('says what is missing when the server finds the event not ready', async () => {
    catalog({ layout: SAVED_LAYOUT });
    const base = handler;
    handler = (call) => (call.method === 'POST' ? problem(409, 'EVENT_NOT_READY') : base(call));
    renderEvent();

    await clickPublish();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, publish' }));

    expect(await screen.findByText(/not ready to publish/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Publish event' })).toBeInTheDocument();
  });

  it('offers a reload when the event changed or is already published', async () => {
    catalog({ layout: SAVED_LAYOUT });
    const base = handler;
    handler = (call) => (call.method === 'POST' ? problem(409, 'EVENT_ALREADY_PUBLISHED') : base(call));
    renderEvent();

    await clickPublish();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, publish' }));

    expect(await screen.findByText('This event is already published.')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Reload the event' }).length).toBeGreaterThan(0);
  });

  it('warns that unsaved layout changes are not published', async () => {
    catalog({ layout: SAVED_LAYOUT });
    renderEvent();

    await screen.findByLabelText('Tier 1 name');
    type('Tier 1 price', '99');

    expect(await screen.findByText(/unsaved layout changes/)).toBeInTheDocument();
  });
});
