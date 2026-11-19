import type { LayoutDetail, LayoutRequest } from '../api/organizer';
import type { FieldError } from '../api/problem';

export const CURRENCIES = ['UZS', 'USD', 'EUR'] as const;
export const MAX_TIERS = 10;
export const MAX_SECTIONS = 30;
export const MAX_SEATS = 5000;
export const MAX_ROWS = 26;
export const MAX_SEATS_PER_ROW = 100;
export const NAME_MAX = 50;
const MAX_PRICE_MINOR = 1_000_000_000_000;

/** One price tier as typed: the price is in whole currency units (for example 150000 or 150.50). */
export interface TierRow {
  name: string;
  price: string;
}

/** One section as typed. `tier` is the name of one of the tiers. */
export interface SectionRow {
  name: string;
  tier: string;
  rows: string;
  seatsPerRow: string;
}

export interface LayoutForm {
  currency: string;
  tiers: TierRow[];
  sections: SectionRow[];
}

export interface RowErrors {
  name?: string;
  price?: string;
  tier?: string;
  rows?: string;
  seatsPerRow?: string;
}

/** Problems of the whole layout (`general`) and of single tiers and sections, by their position in the lists. */
export interface LayoutErrors {
  general: string[];
  currency?: string;
  tiers: Record<number, RowErrors>;
  sections: Record<number, RowErrors>;
}

export const NO_LAYOUT_ERRORS: LayoutErrors = { general: [], tiers: {}, sections: {} };

/** What a new layout starts with, so the organizer sees how the pieces fit. */
export function starterLayout(): LayoutForm {
  return {
    currency: 'UZS',
    tiers: [{ name: 'Standard', price: '' }],
    sections: [{ name: 'Main hall', tier: 'Standard', rows: '10', seatsPerRow: '20' }],
  };
}

/** 150000 (minor units) as "1500", 150050 as "1500.50". Every supported currency has two decimals. */
export function minorToMajor(minor: number): string {
  const whole = Math.floor(minor / 100);
  const fraction = minor % 100;
  return fraction === 0 ? String(whole) : `${whole}.${String(fraction).padStart(2, '0')}`;
}

/** "1500.5" as 150050, or null when it is not a price above zero that fits the limit. */
export function majorToMinor(text: string): number | null {
  const match = /^(\d{1,11})(?:\.(\d{1,2}))?$/.exec(text.trim());
  if (match === null) {
    return null;
  }
  const minor = Number(match[1]) * 100 + Number((match[2] ?? '').padEnd(2, '0'));
  return minor >= 1 && minor <= MAX_PRICE_MINOR ? minor : null;
}

export function formFromLayout(layout: LayoutDetail): LayoutForm {
  if (layout.currency === null || layout.priceTiers.length === 0) {
    return starterLayout();
  }
  return {
    currency: layout.currency,
    tiers: layout.priceTiers.map((tier) => ({ name: tier.name, price: minorToMajor(tier.priceMinor) })),
    sections: layout.sections.map((section) => ({
      name: section.name,
      tier: section.tier,
      rows: String(section.rows),
      seatsPerRow: String(section.seatsPerRow),
    })),
  };
}

function count(text: string, max: number): number | null {
  if (!/^\d{1,4}$/.test(text.trim())) {
    return null;
  }
  const value = Number(text.trim());
  return value >= 1 && value <= max ? value : null;
}

/** Seats in the layout as typed; sections that are not filled in correctly count as zero. */
export function seatTotal(form: LayoutForm): number {
  return form.sections.reduce((sum, section) => {
    const rows = count(section.rows, MAX_ROWS);
    const perRow = count(section.seatsPerRow, MAX_SEATS_PER_ROW);
    return rows !== null && perRow !== null ? sum + rows * perRow : sum;
  }, 0);
}

function nameProblem(raw: string, label: string): string | undefined {
  const name = raw.trim();
  if (name === '') {
    return `${label} name is required.`;
  }
  if (Array.from(name).length > NAME_MAX) {
    return `${label} name can have at most ${NAME_MAX} characters.`;
  }
  return undefined;
}

const key = (name: string) => name.trim().toLowerCase();

/** The same rules the server applies (contract: LayoutRequest). Returns null when the layout is fine. */
export function validateLayout(form: LayoutForm): LayoutErrors | null {
  const errors: LayoutErrors = { general: [], tiers: {}, sections: {} };
  if (!(CURRENCIES as readonly string[]).includes(form.currency)) {
    errors.currency = 'Choose a currency.';
  }
  if (form.tiers.length < 1 || form.tiers.length > MAX_TIERS) {
    errors.general.push(`A layout needs from 1 to ${MAX_TIERS} price tiers.`);
  }
  if (form.sections.length < 1 || form.sections.length > MAX_SECTIONS) {
    errors.general.push(`A layout needs from 1 to ${MAX_SECTIONS} sections.`);
  }
  const tierNames = new Set<string>();
  form.tiers.forEach((tier, index) => {
    const problems: RowErrors = {};
    problems.name = nameProblem(tier.name, 'Tier');
    if (problems.name === undefined) {
      if (tierNames.has(key(tier.name))) {
        problems.name = 'Tier names must be different.';
      }
      tierNames.add(key(tier.name));
    }
    if (majorToMinor(tier.price) === null) {
      problems.price = 'Enter a price above zero, for example 150000 or 150.50.';
    }
    if (problems.name || problems.price) {
      errors.tiers[index] = problems;
    }
  });
  const sectionNames = new Set<string>();
  form.sections.forEach((section, index) => {
    const problems: RowErrors = {};
    problems.name = nameProblem(section.name, 'Section');
    if (problems.name === undefined) {
      if (sectionNames.has(key(section.name))) {
        problems.name = 'Section names must be different.';
      }
      sectionNames.add(key(section.name));
    }
    if (!tierNames.has(key(section.tier))) {
      problems.tier = 'Choose one of the price tiers.';
    }
    if (count(section.rows, MAX_ROWS) === null) {
      problems.rows = `Rows is a whole number from 1 to ${MAX_ROWS}.`;
    }
    if (count(section.seatsPerRow, MAX_SEATS_PER_ROW) === null) {
      problems.seatsPerRow = `Seats per row is a whole number from 1 to ${MAX_SEATS_PER_ROW}.`;
    }
    if (Object.values(problems).some((problem) => problem !== undefined)) {
      errors.sections[index] = problems;
    }
  });
  if (seatTotal(form) > MAX_SEATS) {
    errors.general.push(`A layout can have at most ${MAX_SEATS} seats in total.`);
  }
  const found =
    errors.general.length > 0 ||
    errors.currency !== undefined ||
    Object.keys(errors.tiers).length > 0 ||
    Object.keys(errors.sections).length > 0;
  return found ? errors : null;
}

/** Call only when validateLayout returned null. */
export function toLayoutRequest(form: LayoutForm): LayoutRequest {
  const tierNameByKey = new Map(form.tiers.map((tier) => [key(tier.name), tier.name.trim()]));
  return {
    currency: form.currency as LayoutRequest['currency'],
    priceTiers: form.tiers.map((tier) => ({ name: tier.name.trim(), priceMinor: majorToMinor(tier.price) ?? 0 })),
    sections: form.sections.map((section) => ({
      name: section.name.trim(),
      tier: tierNameByKey.get(key(section.tier)) ?? section.tier.trim(),
      rows: Number(section.rows.trim()),
      seatsPerRow: Number(section.seatsPerRow.trim()),
    })),
  };
}

const TIER_FIELD = /^priceTiers\[(\d+)\]\.(name|priceMinor)$/;
const SECTION_FIELD = /^sections\[(\d+)\]\.(name|tier|rows|seatsPerRow)$/;

/** The server's problems, placed on the tier or section they name; the others are listed as general. */
export function layoutErrorsFromServer(fieldErrors: FieldError[]): LayoutErrors {
  const errors: LayoutErrors = { general: [], tiers: {}, sections: {} };
  for (const { field, problem } of fieldErrors) {
    const tier = TIER_FIELD.exec(field);
    const section = SECTION_FIELD.exec(field);
    if (field === 'currency') {
      errors.currency ??= problem;
    } else if (tier) {
      const row = (errors.tiers[Number(tier[1])] ??= {});
      const target = tier[2] === 'priceMinor' ? 'price' : 'name';
      row[target] ??= problem;
    } else if (section) {
      const row = (errors.sections[Number(section[1])] ??= {});
      row[section[2] as 'name' | 'tier' | 'rows' | 'seatsPerRow'] ??= problem;
    } else {
      errors.general.push(`${field}: ${problem}`);
    }
  }
  return errors;
}
