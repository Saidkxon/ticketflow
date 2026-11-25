#!/usr/bin/env node
// Load check of seat availability and holds (NFR-3 targets from docs/plans/phase-4b-booking.md, Phase 5 row):
//   availability: p95 under 200 ms with 50 browsers polling the seat map,
//   holds:        p95 under 500 ms with 50 customers holding seats at the same moment,
//   and a contest for the same two seats has exactly one winner (a seat is never sold twice, NFR-1).
//
// Start the stack first (docker compose -f deploy/compose.yaml --profile app up -d --build --wait) and raise the
// gateway's rate limits, which are made for people, not for a test (see docs/runbooks/load-check.md).
// Usage: node scripts/load-check.mjs [--base http://localhost:18090] [--users 50] [--pollers 50] [--seconds 30]
// Needs Node 20 or newer and an organizer account: LOAD_ORGANIZER_EMAIL and LOAD_ORGANIZER_PASSWORD.
// It registers its own customers (load-<time>-<n>@example.com) and publishes one event ("Load check event ...").

const options = parseArguments(process.argv.slice(2));
const BASE = options.base.replace(/\/+$/, '');
const TARGETS = { availabilityP95Ms: 200, holdP95Ms: 500 };
const PASSWORD = 'load check password 1';

function parseArguments(args) {
  const result = { base: process.env.LOAD_BASE_URL ?? 'http://localhost:18090', users: 50, pollers: 50, seconds: 30 };
  for (let i = 0; i < args.length; i += 2) {
    const name = args[i]?.replace(/^--/, '');
    const value = args[i + 1];
    if (!(name in result) || value === undefined) {
      console.error(`Unknown or incomplete option: ${args[i]}`);
      console.error('Usage: node scripts/load-check.mjs [--base URL] [--users N] [--pollers N] [--seconds N]');
      process.exit(2);
    }
    result[name] = name === 'base' ? value : Number(value);
    if (name !== 'base' && !(result[name] >= 1)) {
      console.error(`--${name} must be a number of at least 1`);
      process.exit(2);
    }
  }
  return result;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** One HTTP call; the time includes reading the whole answer. */
async function call(method, path, { token, body, headers = {} } = {}) {
  const started = performance.now();
  let status = 0;
  let text = '';
  let answerHeaders = new Headers();
  try {
    const response = await fetch(BASE + path, {
      method,
      headers: {
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...headers,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    status = response.status;
    answerHeaders = response.headers;
    text = await response.text();
  } catch (error) {
    text = String(error);
  }
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    // Not JSON: the text stays.
  }
  return { status, ms: performance.now() - started, headers: answerHeaders, json, text };
}

const authHeaders = { Origin: BASE, 'X-TicketFlow-Client': 'web' };
const login = (email, password) =>
  call('POST', '/api/v1/auth/login', { body: { email, password }, headers: authHeaders });

/**
 * The version of an event for If-Match. A proxy that compresses an answer turns its ETag weak (W/"3"), and the server
 * wants the strong form ("3"), so the weak mark is taken off. (curl does not ask for compression; fetch does.)
 */
const versionOf = (answer) => answer.headers.get('etag')?.replace(/^W\//, '');

function newUuid() {
  return crypto.randomUUID();
}

function percentile(sorted, fraction) {
  if (sorted.length === 0) return NaN;
  return sorted[Math.min(sorted.length - 1, Math.ceil(fraction * sorted.length) - 1)];
}

function summarize(samples) {
  const times = samples.map((sample) => sample.ms).sort((a, b) => a - b);
  const byStatus = {};
  for (const sample of samples) byStatus[sample.status] = (byStatus[sample.status] ?? 0) + 1;
  return { count: samples.length, p50: percentile(times, 0.5), p95: percentile(times, 0.95), p99: percentile(times, 0.99), byStatus };
}

const fixed = (value) => (Number.isNaN(value) ? '-' : value.toFixed(0).padStart(5));

function row(name, summary) {
  const statuses = Object.entries(summary.byStatus).map(([status, count]) => `${status}x${count}`).join(' ');
  console.log(`${name.padEnd(30)} n=${String(summary.count).padStart(4)}  p50 ${fixed(summary.p50)} ms  p95 ${fixed(summary.p95)} ms  p99 ${fixed(summary.p99)} ms  ${statuses}`);
}

/** Runs the tasks with at most "limit" at the same time. */
async function inParallel(items, limit, task) {
  const results = new Array(items.length);
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(limit, items.length) }, async () => {
      while (next < items.length) {
        const index = next++;
        results[index] = await task(items[index], index);
      }
    }),
  );
  return results;
}

function fail(message, answer) {
  console.error(`\nFAIL: ${message}`);
  if (answer) console.error(`  answer: ${answer.status} ${answer.text.slice(0, 300)}`);
  if (answer?.status === 429) {
    console.error('  The gateway refused calls because of its rate limit. Raise the limits as docs/runbooks/load-check.md says.');
  }
  process.exit(1);
}

async function main() {
  const email = process.env.LOAD_ORGANIZER_EMAIL;
  const password = process.env.LOAD_ORGANIZER_PASSWORD;
  if (!email || !password) {
    console.error('Set LOAD_ORGANIZER_EMAIL and LOAD_ORGANIZER_PASSWORD to an organizer account (see docs/runbooks/load-check.md).');
    process.exit(2);
  }
  console.log(`Load check against ${BASE}: ${options.users} customers, ${options.pollers} pollers, at least ${options.seconds} s`);

  // 1. An event with seats.
  const organizerLogin = await login(email, password);
  if (organizerLogin.status !== 200) fail('the organizer could not sign in', organizerLogin);
  const organizer = organizerLogin.json.accessToken;
  const draft = await call('POST', '/api/v1/events', {
    token: organizer,
    body: { title: `Load check event ${Date.now()}`, startsAt: '2099-01-01T18:00:00Z', venueName: 'Load Hall', city: 'Tashkent' },
  });
  if (draft.status !== 201) fail('the organizer could not create an event (is this account an organizer?)', draft);
  const eventId = draft.json.id;
  const seatsNeeded = options.users * 2 + 2;
  const seatsPerRow = 20;
  const rows = Math.min(26, Math.ceil(seatsNeeded / seatsPerRow));
  if (rows * seatsPerRow < seatsNeeded) fail('too many users for one section of at most 520 seats');
  const layout = await call('PUT', `/api/v1/events/${eventId}/layout`, {
    token: organizer,
    headers: { 'If-Match': versionOf(draft) },
    body: {
      currency: 'UZS',
      priceTiers: [{ name: 'Standard', priceMinor: 100000 }],
      sections: [{ name: 'Load', tier: 'Standard', rows, seatsPerRow }],
    },
  });
  if (layout.status !== 200) fail('the layout was not saved', layout);
  const published = await call('POST', `/api/v1/events/${eventId}/publish`, {
    token: organizer,
    headers: { 'If-Match': versionOf(layout) },
  });
  if (published.status !== 200) fail('the event was not published', published);
  let provisioned = false;
  for (let i = 0; i < 60 && !provisioned; i++) {
    provisioned = (await call('GET', `/api/v1/events/${eventId}/availability`)).status === 200;
    if (!provisioned) await sleep(1000);
  }
  if (!provisioned) fail('Booking did not get the seats within 60 seconds');
  const seatList = await call('GET', `/api/v1/events/${eventId}/layout`);
  const seats = seatList.json.sections.flatMap((section) => section.rows.flatMap((r) => r.seats.map((seat) => seat.id)));
  console.log(`Event ${eventId} published with ${seats.length} seats`);

  // 2. The customers.
  const stamp = Date.now();
  const customers = await inParallel(Array.from({ length: options.users }, (_, i) => i), 10, async (i) => {
    const address = `load-${stamp}-${i}@example.com`;
    const registered = await call('POST', '/api/v1/auth/register', {
      body: { email: address, password: PASSWORD, displayName: `Load ${i}` },
      headers: authHeaders,
    });
    if (registered.status !== 201) fail(`customer ${i} could not register`, registered);
    const session = await login(address, PASSWORD);
    if (session.status !== 200) fail(`customer ${i} could not sign in`, session);
    return { token: session.json.accessToken };
  });
  console.log(`${customers.length} customers signed in`);

  // 3. The pollers run during everything below, as browsers with the seat map open would (ADR-0011).
  const availability = [];
  let polling = true;
  const pollers = Array.from({ length: options.pollers }, async () => {
    let etag = null;
    await sleep(Math.random() * 3000);
    while (polling) {
      const answer = await call('GET', `/api/v1/events/${eventId}/availability`, { headers: etag ? { 'If-None-Match': etag } : {} });
      availability.push(answer);
      etag = answer.headers.get('etag') ?? etag;
      await sleep(3000 * (0.8 + Math.random() * 0.4));
    }
  });
  const startedAt = performance.now();

  // 4. Everybody holds two seats of their own at the same moment.
  const holds = await Promise.all(
    customers.map((customer, i) =>
      call('POST', '/api/v1/bookings', {
        token: customer.token,
        headers: { 'Idempotency-Key': newUuid() },
        body: { eventId, seatIds: [seats[i * 2], seats[i * 2 + 1]] },
      }),
    ),
  );
  const held = holds.filter((answer) => answer.status === 201);
  const releases = await Promise.all(
    holds.map((answer, i) =>
      answer.status === 201 ? call('POST', `/api/v1/bookings/${answer.json.id}/release`, { token: customers[i].token }) : null,
    ),
  );

  // 5. Everybody wants the same two seats: exactly one may win.
  const prize = [seats[options.users * 2], seats[options.users * 2 + 1]];
  const contest = await Promise.all(
    customers.map((customer) =>
      call('POST', '/api/v1/bookings', { token: customer.token, headers: { 'Idempotency-Key': newUuid() }, body: { eventId, seatIds: prize } }),
    ),
  );
  const winners = contest.filter((answer) => answer.status === 201);
  await Promise.all(
    winners.map((answer) => {
      const index = contest.indexOf(answer);
      return call('POST', `/api/v1/bookings/${answer.json.id}/release`, { token: customers[index].token });
    }),
  );

  // 6. Poll for at least the asked time, then stop.
  const remaining = options.seconds * 1000 - (performance.now() - startedAt);
  if (remaining > 0) await sleep(remaining);
  polling = false;
  await Promise.all(pollers);

  // 7. The report.
  console.log('');
  const holdSummary = summarize(holds);
  const availabilitySummary = summarize(availability);
  row('holds, own seats, all at once', holdSummary);
  row('releases', summarize(releases.filter(Boolean)));
  row('holds, same two seats', summarize(contest));
  row('availability polls', availabilitySummary);
  const contestStatuses = Object.fromEntries(
    Object.entries(summarize(contest).byStatus).map(([status, count]) => [status, count]),
  );
  console.log('');
  const serverErrors = [...holds, ...contest, ...availability].filter((answer) => answer.status === 0 || answer.status >= 500);
  const limited = [...holds, ...contest, ...availability].filter((answer) => answer.status === 429);
  const results = [
    ['every customer held two seats', held.length === options.users, `${held.length} of ${options.users}`],
    [`holds p95 under ${TARGETS.holdP95Ms} ms`, holdSummary.p95 < TARGETS.holdP95Ms, `${fixed(holdSummary.p95).trim()} ms`],
    [`availability p95 under ${TARGETS.availabilityP95Ms} ms`, availabilitySummary.p95 < TARGETS.availabilityP95Ms, `${fixed(availabilitySummary.p95).trim()} ms`],
    ['the contest for two seats has exactly one winner', winners.length === 1, `${winners.length} winner(s), ${JSON.stringify(contestStatuses)}`],
    ['no server errors and no timeouts', serverErrors.length === 0, `${serverErrors.length} failed call(s)`],
    ['no call was refused by the rate limit', limited.length === 0, `${limited.length} refused call(s)`],
  ];
  let failed = false;
  for (const [name, ok, detail] of results) {
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${name} (${detail})`);
    failed ||= !ok;
  }
  if (limited.length > 0) console.log('\nThe gateway refused calls because of its rate limit: raise the limits (docs/runbooks/load-check.md) and run again.');
  process.exit(failed ? 1 : 0);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
