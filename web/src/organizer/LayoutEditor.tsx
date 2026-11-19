import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type SyntheticEvent } from 'react';
import { saveLayout, type EventDetail, type LayoutDetail } from '../api/organizer';
import { ApiError } from '../api/problem';
import { useSessionStore } from '../auth/SessionContext';
import {
  CURRENCIES,
  MAX_SECTIONS,
  MAX_SEATS,
  MAX_TIERS,
  NO_LAYOUT_ERRORS,
  formFromLayout,
  layoutErrorsFromServer,
  seatTotal,
  toLayoutRequest,
  validateLayout,
  type LayoutErrors,
  type LayoutForm,
  type RowErrors,
  type SectionRow,
  type TierRow,
} from './layoutForm';
import { messageForOrganizerError, referenceFor } from './messages';

interface Props {
  event: EventDetail;
  layout: LayoutDetail;
  onDirtyChange: (dirty: boolean) => void;
  onReload: () => void;
}

function FieldMessage({ id, message }: { id: string; message?: string }) {
  return message ? (
    <p className="field-error" id={id}>
      {message}
    </p>
  ) : null;
}

/** Edits the price tiers and sections of a draft event and saves them as one layout (FR-3, ADR-0012). */
export function LayoutEditor({ event, layout, onDirtyChange, onReload }: Props) {
  const store = useSessionStore();
  const queryClient = useQueryClient();
  const [form, setForm] = useState<LayoutForm>(() => formFromLayout(layout));
  const [baseline, setBaseline] = useState(() => JSON.stringify(formFromLayout(layout)));
  const [errors, setErrors] = useState<LayoutErrors>(NO_LAYOUT_ERRORS);
  const [failure, setFailure] = useState<unknown>(null);
  const [savedSeats, setSavedSeats] = useState<number | null>(null);
  const [pending, setPending] = useState(false);
  const dirty = JSON.stringify(form) !== baseline;

  useEffect(() => {
    onDirtyChange(dirty);
  }, [dirty, onDirtyChange]);

  function edit(change: (current: LayoutForm) => LayoutForm) {
    setForm(change);
    setSavedSeats(null);
  }

  function updateTier(index: number, patch: Partial<TierRow>) {
    edit((current) => {
      const old = current.tiers[index];
      const tiers = current.tiers.map((tier, at) => (at === index ? { ...tier, ...patch } : tier));
      // A section that used the old name follows the rename, so renaming a tier never breaks a section.
      const sections =
        patch.name === undefined
          ? current.sections
          : current.sections.map((section) =>
              section.tier === old.name ? { ...section, tier: patch.name ?? section.tier } : section,
            );
      return { ...current, tiers, sections };
    });
  }

  function removeTier(index: number) {
    edit((current) => {
      const removed = current.tiers[index].name;
      const tiers = current.tiers.filter((_, at) => at !== index);
      const fallback = tiers[0]?.name ?? '';
      const sections = current.sections.map((section) =>
        section.tier === removed ? { ...section, tier: fallback } : section,
      );
      return { ...current, tiers, sections };
    });
  }

  function updateSection(index: number, patch: Partial<SectionRow>) {
    edit((current) => ({
      ...current,
      sections: current.sections.map((section, at) => (at === index ? { ...section, ...patch } : section)),
    }));
  }

  async function submit(submitted: SyntheticEvent<HTMLFormElement>) {
    submitted.preventDefault();
    setFailure(null);
    setSavedSeats(null);
    const problems = validateLayout(form);
    setErrors(problems ?? NO_LAYOUT_ERRORS);
    if (problems !== null) {
      return;
    }
    setPending(true);
    try {
      const saved = await saveLayout(
        (input, init) => store.authorizedFetch(input, init),
        event.id,
        event.version,
        toLayoutRequest(form),
      );
      setBaseline(JSON.stringify(form));
      setSavedSeats(saved.layout.totalSeats);
      queryClient.setQueryData(['my-layout', event.id], saved.layout);
      queryClient.setQueryData<EventDetail>(['my-event', event.id], (old) =>
        old ? { ...old, version: saved.version } : old,
      );
    } catch (caught) {
      setFailure(caught);
      if (caught instanceof ApiError) {
        setErrors(layoutErrorsFromServer(caught.fieldErrors));
      }
    } finally {
      setPending(false);
    }
  }

  const total = seatTotal(form);
  const tierProblems = (index: number): RowErrors => errors.tiers[index] ?? {};
  const sectionProblems = (index: number): RowErrors => errors.sections[index] ?? {};

  return (
    <form className="layout-form" noValidate onSubmit={(submitted) => void submit(submitted)}>
      <div className="field">
        <label>
          Currency
          <select
            value={form.currency}
            onChange={(changed) => edit((current) => ({ ...current, currency: changed.target.value }))}
          >
            {CURRENCIES.map((currency) => (
              <option key={currency} value={currency}>
                {currency}
              </option>
            ))}
          </select>
        </label>
        <FieldMessage id="currency-error" message={errors.currency} />
      </div>

      <h3>Price tiers</h3>
      <p className="hint">
        A tier is a price. Prices are in whole {form.currency}; cents are allowed, for example 150.50.
      </p>
      {form.tiers.map((tier, index) => (
        <fieldset key={index}>
          <legend>Tier {index + 1}</legend>
          <div className="field">
            <label>
              {`Tier ${index + 1} name`}
              <input
                type="text"
                maxLength={50}
                value={tier.name}
                aria-invalid={tierProblems(index).name ? true : undefined}
                onChange={(changed) => updateTier(index, { name: changed.target.value })}
              />
            </label>
            <FieldMessage id={`tier-${index}-name-error`} message={tierProblems(index).name} />
          </div>
          <div className="field">
            <label>
              {`Tier ${index + 1} price`}
              <input
                type="text"
                inputMode="decimal"
                value={tier.price}
                aria-invalid={tierProblems(index).price ? true : undefined}
                onChange={(changed) => updateTier(index, { price: changed.target.value })}
              />
            </label>
            <FieldMessage id={`tier-${index}-price-error`} message={tierProblems(index).price} />
          </div>
          <button type="button" onClick={() => removeTier(index)} disabled={form.tiers.length <= 1}>
            {`Remove tier ${index + 1}`}
          </button>
        </fieldset>
      ))}
      <button
        type="button"
        onClick={() => edit((current) => ({ ...current, tiers: [...current.tiers, { name: '', price: '' }] }))}
        disabled={form.tiers.length >= MAX_TIERS}
      >
        Add tier
      </button>

      <h3>Sections</h3>
      <p className="hint">A section is a block of seats in rows A, B, C… with the same number of seats in each row.</p>
      {form.sections.map((section, index) => (
        <fieldset key={index}>
          <legend>Section {index + 1}</legend>
          <div className="field">
            <label>
              {`Section ${index + 1} name`}
              <input
                type="text"
                maxLength={50}
                value={section.name}
                aria-invalid={sectionProblems(index).name ? true : undefined}
                onChange={(changed) => updateSection(index, { name: changed.target.value })}
              />
            </label>
            <FieldMessage id={`section-${index}-name-error`} message={sectionProblems(index).name} />
          </div>
          <div className="field">
            <label>
              {`Section ${index + 1} tier`}
              <select
                value={section.tier}
                aria-invalid={sectionProblems(index).tier ? true : undefined}
                onChange={(changed) => updateSection(index, { tier: changed.target.value })}
              >
                {!form.tiers.some((tier) => tier.name === section.tier) && <option value={section.tier}>Choose…</option>}
                {form.tiers.map((tier, at) => (
                  <option key={at} value={tier.name}>
                    {tier.name === '' ? `(tier ${at + 1} has no name)` : tier.name}
                  </option>
                ))}
              </select>
            </label>
            <FieldMessage id={`section-${index}-tier-error`} message={sectionProblems(index).tier} />
          </div>
          <div className="field">
            <label>
              {`Section ${index + 1} rows`}
              <input
                type="text"
                inputMode="numeric"
                value={section.rows}
                aria-invalid={sectionProblems(index).rows ? true : undefined}
                onChange={(changed) => updateSection(index, { rows: changed.target.value })}
              />
            </label>
            <FieldMessage id={`section-${index}-rows-error`} message={sectionProblems(index).rows} />
          </div>
          <div className="field">
            <label>
              {`Section ${index + 1} seats per row`}
              <input
                type="text"
                inputMode="numeric"
                value={section.seatsPerRow}
                aria-invalid={sectionProblems(index).seatsPerRow ? true : undefined}
                onChange={(changed) => updateSection(index, { seatsPerRow: changed.target.value })}
              />
            </label>
            <FieldMessage id={`section-${index}-seats-error`} message={sectionProblems(index).seatsPerRow} />
          </div>
          <button
            type="button"
            onClick={() => edit((current) => ({ ...current, sections: current.sections.filter((_, at) => at !== index) }))}
            disabled={form.sections.length <= 1}
          >
            {`Remove section ${index + 1}`}
          </button>
        </fieldset>
      ))}
      <button
        type="button"
        onClick={() =>
          edit((current) => ({
            ...current,
            sections: [
              ...current.sections,
              { name: '', tier: current.tiers[0]?.name ?? '', rows: '', seatsPerRow: '' },
            ],
          }))
        }
        disabled={form.sections.length >= MAX_SECTIONS}
      >
        Add section
      </button>

      <p role="status">
        Total: {total} seats (at most {MAX_SEATS}).
      </p>
      {errors.general.length > 0 && (
        <ul className="field-error">
          {errors.general.map((message) => (
            <li key={message}>{message}</li>
          ))}
        </ul>
      )}
      {failure !== null && (
        <div role="alert">
          <p>{messageForOrganizerError(failure)}</p>
          {referenceFor(failure) && (
            <p>
              <small>Reference: {referenceFor(failure)}</small>
            </p>
          )}
          {failure instanceof ApiError && failure.code === 'PRECONDITION_FAILED' && (
            <button type="button" onClick={onReload}>
              Reload the event
            </button>
          )}
        </div>
      )}
      {savedSeats !== null && <p role="status">Layout saved. {savedSeats} seats.</p>}
      <button type="submit" disabled={pending}>
        Save layout
      </button>
    </form>
  );
}
