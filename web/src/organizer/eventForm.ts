import type { EventDetail, SaveEventRequest } from '../api/organizer';
import type { FieldError } from '../api/problem';

/** What the event form holds. Every value is text, as typed; toRequest turns it into the API body. */
export interface EventFormValues {
  title: string;
  startsAt: string; // the value of a datetime-local input, in the browser's time zone
  venueName: string;
  city: string;
  venueLatitude: string;
  venueLongitude: string;
}

export type EventFormErrors = Partial<Record<keyof EventFormValues, string>>;

export const EMPTY_EVENT_FORM: EventFormValues = {
  title: '',
  startsAt: '',
  venueName: '',
  city: '',
  venueLatitude: '',
  venueLongitude: '',
};

const NUMBER = /^-?\d{1,3}(\.\d{1,15})?$/;

function pad(value: number): string {
  return String(value).padStart(2, '0');
}

/** An ISO time from the API as the value of a datetime-local input (local time, minutes). */
export function toLocalInput(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** The value of a datetime-local input as an ISO time in UTC with a trailing Z, or null when it is not a time. */
export function fromLocalInput(value: string): string | null {
  if (value === '') {
    return null;
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString().replace('.000Z', 'Z');
}

export function formFromEvent(event: EventDetail): EventFormValues {
  return {
    title: event.title,
    startsAt: toLocalInput(event.startsAt),
    venueName: event.venueName,
    city: event.city,
    venueLatitude: event.venueLatitude == null ? '' : String(event.venueLatitude),
    venueLongitude: event.venueLongitude == null ? '' : String(event.venueLongitude),
  };
}

function length(text: string): number {
  return Array.from(text).length;
}

/** The same rules the server applies (contract: SaveEventRequest), so a mistake is caught before the request. */
export function validateEventForm(values: EventFormValues, now: Date = new Date()): EventFormErrors {
  const errors: EventFormErrors = {};
  const texts: [keyof EventFormValues, string, number][] = [
    ['title', 'Title', 200],
    ['venueName', 'Venue name', 200],
    ['city', 'City', 100],
  ];
  for (const [key, label, max] of texts) {
    const text = values[key].trim();
    if (text === '') {
      errors[key] = `${label} is required.`;
    } else if (length(text) > max) {
      errors[key] = `${label} can have at most ${max} characters.`;
    }
  }
  const startsAt = fromLocalInput(values.startsAt);
  if (startsAt === null) {
    errors.startsAt = 'Choose the date and time the event starts.';
  } else if (new Date(startsAt).getTime() <= now.getTime()) {
    errors.startsAt = 'The start must be in the future.';
  }
  const latitude = values.venueLatitude.trim();
  const longitude = values.venueLongitude.trim();
  if (latitude !== '' || longitude !== '') {
    if (latitude === '') {
      errors.venueLatitude = 'Give the latitude too, or clear the longitude.';
    } else if (!NUMBER.test(latitude) || Math.abs(Number(latitude)) > 90) {
      errors.venueLatitude = 'Latitude is a number from -90 to 90.';
    }
    if (longitude === '') {
      errors.venueLongitude = 'Give the longitude too, or clear the latitude.';
    } else if (!NUMBER.test(longitude) || Math.abs(Number(longitude)) > 180) {
      errors.venueLongitude = 'Longitude is a number from -180 to 180.';
    }
  }
  return errors;
}

/** Call only when validateEventForm found no errors. */
export function toRequest(values: EventFormValues): SaveEventRequest {
  const latitude = values.venueLatitude.trim();
  const longitude = values.venueLongitude.trim();
  return {
    title: values.title.trim(),
    startsAt: fromLocalInput(values.startsAt) ?? '',
    venueName: values.venueName.trim(),
    city: values.city.trim(),
    venueLatitude: latitude === '' ? null : Number(latitude),
    venueLongitude: longitude === '' ? null : Number(longitude),
  };
}

const FIELDS: ReadonlySet<string> = new Set(Object.keys(EMPTY_EVENT_FORM));

/** The server's field problems, keyed by form field. A problem on a field the form does not have is dropped. */
export function errorsFromServer(fieldErrors: FieldError[]): EventFormErrors {
  const errors: EventFormErrors = {};
  for (const { field, problem } of fieldErrors) {
    if (FIELDS.has(field)) {
      errors[field as keyof EventFormValues] ??= problem;
    }
  }
  return errors;
}
