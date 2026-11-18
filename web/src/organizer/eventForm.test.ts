import { describe, expect, it } from 'vitest';
import {
  EMPTY_EVENT_FORM,
  errorsFromServer,
  formFromEvent,
  fromLocalInput,
  toLocalInput,
  toRequest,
  validateEventForm,
  type EventFormValues,
} from './eventForm';

const NOW = new Date('2026-11-20T10:00:00Z');

function valid(overrides: Partial<EventFormValues> = {}): EventFormValues {
  return {
    title: 'Jazz Night',
    startsAt: '2099-01-10T18:00',
    venueName: 'Main Hall',
    city: 'Tashkent',
    venueLatitude: '',
    venueLongitude: '',
    ...overrides,
  };
}

describe('validateEventForm', () => {
  it('accepts a complete form without coordinates', () => {
    expect(validateEventForm(valid(), NOW)).toEqual({});
  });

  it('asks for every required field', () => {
    const errors = validateEventForm(EMPTY_EVENT_FORM, NOW);

    expect(Object.keys(errors).sort()).toEqual(['city', 'startsAt', 'title', 'venueName']);
  });

  it('counts only text that is not spaces', () => {
    expect(validateEventForm(valid({ title: '   ' }), NOW).title).toMatch(/required/);
  });

  it('limits the length of the texts', () => {
    const errors = validateEventForm(valid({ title: 'x'.repeat(201), city: 'y'.repeat(101) }), NOW);

    expect(errors.title).toMatch(/200/);
    expect(errors.city).toMatch(/100/);
    expect(validateEventForm(valid({ title: 'x'.repeat(200) }), NOW).title).toBeUndefined();
  });

  it('wants a start in the future', () => {
    const errors = validateEventForm(valid({ startsAt: '2020-01-01T10:00' }), NOW);

    expect(errors.startsAt).toMatch(/future/);
  });

  it('wants both coordinates or neither', () => {
    expect(validateEventForm(valid({ venueLatitude: '41.3' }), NOW).venueLongitude).toBeDefined();
    expect(validateEventForm(valid({ venueLongitude: '69.2' }), NOW).venueLatitude).toBeDefined();
    expect(validateEventForm(valid({ venueLatitude: '41.3', venueLongitude: '69.2' }), NOW)).toEqual({});
  });

  it('keeps the coordinates in range and plain numbers', () => {
    expect(validateEventForm(valid({ venueLatitude: '91', venueLongitude: '10' }), NOW).venueLatitude).toBeDefined();
    expect(validateEventForm(valid({ venueLatitude: '10', venueLongitude: '-181' }), NOW).venueLongitude).toBeDefined();
    for (const bad of ['NaN', '1e2', '41,3', 'abc', '--1']) {
      expect(validateEventForm(valid({ venueLatitude: bad, venueLongitude: '10' }), NOW).venueLatitude).toBeDefined();
    }
    expect(validateEventForm(valid({ venueLatitude: '-90', venueLongitude: '180' }), NOW)).toEqual({});
  });
});

describe('toRequest', () => {
  it('trims the texts and turns the time into UTC', () => {
    const request = toRequest(valid({ title: '  Jazz Night ', city: ' Tashkent' }));

    expect(request.title).toBe('Jazz Night');
    expect(request.city).toBe('Tashkent');
    expect(request.startsAt).toBe(new Date('2099-01-10T18:00').toISOString().replace('.000Z', 'Z'));
    expect(request.startsAt.endsWith('Z')).toBe(true);
  });

  it('sends null for empty coordinates and numbers for given ones', () => {
    expect(toRequest(valid())).toMatchObject({ venueLatitude: null, venueLongitude: null });
    expect(toRequest(valid({ venueLatitude: ' 41.3111', venueLongitude: '69.2797 ' }))).toMatchObject({
      venueLatitude: 41.3111,
      venueLongitude: 69.2797,
    });
  });
});

describe('times', () => {
  it('goes from the API to the input and back to the same minute', () => {
    const iso = '2099-01-10T18:00:00Z';

    expect(fromLocalInput(toLocalInput(iso))).toBe(iso);
  });

  it('treats nonsense as no time', () => {
    expect(fromLocalInput('')).toBeNull();
    expect(fromLocalInput('not a time')).toBeNull();
    expect(toLocalInput('not a time')).toBe('');
  });
});

describe('formFromEvent', () => {
  it('fills the form from an event, with empty coordinates when there are none', () => {
    const form = formFromEvent({
      id: 'e1',
      title: 'Show',
      startsAt: '2099-01-10T18:00:00Z',
      venueName: 'Hall',
      city: 'Tashkent',
      venueLatitude: null,
      venueLongitude: null,
      status: 'DRAFT',
      version: 3,
    });

    expect(form).toMatchObject({ title: 'Show', venueName: 'Hall', city: 'Tashkent', venueLatitude: '' });
  });
});

describe('errorsFromServer', () => {
  it('keeps the first problem of each known field and drops the rest', () => {
    const errors = errorsFromServer([
      { field: 'city', problem: 'must not be blank' },
      { field: 'city', problem: 'second' },
      { field: 'somethingElse', problem: 'ignored' },
    ]);

    expect(errors).toEqual({ city: 'must not be blank' });
  });
});
