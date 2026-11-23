import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { makeLayout } from '../test/layouts';
import { SeatMap } from './SeatMap';

const layout = makeLayout([
  { name: 'Main hall', rows: 2, seatsPerRow: 5 },
  { name: 'Balcony', tier: 'VIP', rows: 1, seatsPerRow: 3 },
]);

function renderMap(props: Partial<Parameters<typeof SeatMap>[0]> = {}) {
  const onToggle = vi.fn();
  const view = render(
    <SeatMap
      layout={layout}
      unavailable={new Set()}
      selected={new Set()}
      interactive
      onToggle={onToggle}
      {...props}
    />,
  );
  return { onToggle, ...view };
}

const seat = (name: RegExp) => screen.getByRole('checkbox', { name });

describe('SeatMap', () => {
  it('draws every seat of the layout and describes each one', () => {
    renderMap();
    expect(screen.getAllByRole('checkbox')).toHaveLength(13);
    expect(seat(/^Main hall, row A, seat 1, Standard, .*UZS$/)).toBeInTheDocument();
    expect(seat(/^Balcony, row A, seat 3, VIP, .*UZS$/)).toBeInTheDocument();
  });

  it('shows the section names with tier and price', () => {
    renderMap();
    expect(screen.getByText(/Main hall · Standard · .*UZS/)).toBeInTheDocument();
    expect(screen.getByText(/Balcony · VIP · .*UZS/)).toBeInTheDocument();
  });

  it('passes a clicked seat on', () => {
    const { onToggle } = renderMap();
    fireEvent.click(seat(/Main hall, row B, seat 2/));
    expect(onToggle).toHaveBeenCalledTimes(1);
    expect(onToggle.mock.calls[0][0]).toMatchObject({ id: 's0-B-2', rowLabel: 'B', number: 2 });
  });

  it('marks a seat that is not available and does not let it be chosen', () => {
    const { onToggle } = renderMap({ unavailable: new Set(['s0-A-1']) });
    const taken = seat(/Main hall, row A, seat 1/);
    expect(taken).toHaveAccessibleName(/not available$/);
    expect(taken).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(taken);
    fireEvent.keyDown(taken, { key: ' ' });
    expect(onToggle).not.toHaveBeenCalled();
  });

  it('marks the chosen seats, also for a screen reader', () => {
    renderMap({ selected: new Set(['s1-A-2']) });
    const chosen = seat(/Balcony, row A, seat 2/);
    expect(chosen).toHaveAttribute('aria-checked', 'true');
    expect(chosen).toHaveAccessibleName(/selected$/);
    expect(seat(/Balcony, row A, seat 1/)).toHaveAttribute('aria-checked', 'false');
  });

  it('lets a chosen seat that has just become unavailable be taken out of the choice', () => {
    const { onToggle } = renderMap({ unavailable: new Set(['s0-A-1']), selected: new Set(['s0-A-1']) });
    fireEvent.keyDown(seat(/Main hall, row A, seat 1/), { key: 'Enter' });
    expect(onToggle).toHaveBeenCalledTimes(1);
  });

  it('has one tab stop only: the first seat, and then the seat that was used last', () => {
    renderMap();
    const stops = screen.getAllByRole('checkbox').filter((s) => s.getAttribute('tabindex') === '0');
    expect(stops).toHaveLength(1);
    expect(stops[0]).toHaveAccessibleName(/Main hall, row A, seat 1/);

    fireEvent.focus(seat(/Main hall, row B, seat 3/));
    expect(seat(/Main hall, row B, seat 3/)).toHaveAttribute('tabindex', '0');
    expect(seat(/Main hall, row A, seat 1/)).toHaveAttribute('tabindex', '-1');
  });

  it('moves with the arrow keys, between rows and sections, and chooses with Space', () => {
    const { onToggle } = renderMap();
    const first = seat(/Main hall, row A, seat 1/);
    first.focus();

    fireEvent.keyDown(first, { key: 'ArrowRight' });
    expect(seat(/Main hall, row A, seat 2/)).toHaveFocus();

    fireEvent.keyDown(document.activeElement as Element, { key: 'ArrowDown' });
    expect(seat(/Main hall, row B, seat 2/)).toHaveFocus();

    fireEvent.keyDown(document.activeElement as Element, { key: 'ArrowDown' });
    expect(seat(/Balcony, row A, seat 2/)).toHaveFocus();

    // The balcony row is shorter: from seat 5 the column is limited to its last seat.
    fireEvent.keyDown(document.activeElement as Element, { key: 'ArrowUp' });
    fireEvent.keyDown(document.activeElement as Element, { key: 'End' });
    expect(seat(/Main hall, row B, seat 5/)).toHaveFocus();
    fireEvent.keyDown(document.activeElement as Element, { key: 'ArrowDown' });
    expect(seat(/Balcony, row A, seat 3/)).toHaveFocus();

    fireEvent.keyDown(document.activeElement as Element, { key: 'Home' });
    expect(seat(/Balcony, row A, seat 1/)).toHaveFocus();
    fireEvent.keyDown(document.activeElement as Element, { key: ' ' });
    expect(onToggle).toHaveBeenCalledTimes(1);
    expect(onToggle.mock.calls[0][0]).toMatchObject({ id: 's1-A-1' });
  });

  it('stays where it is at the edges of the map', () => {
    renderMap();
    const first = seat(/Main hall, row A, seat 1/);
    first.focus();
    fireEvent.keyDown(first, { key: 'ArrowLeft' });
    fireEvent.keyDown(first, { key: 'ArrowUp' });
    expect(first).toHaveFocus();
  });

  it('cannot be used before the first availability answer', () => {
    const { onToggle } = renderMap({ interactive: false });
    expect(screen.getByRole('group', { name: 'Seat map' })).toHaveAttribute('aria-busy', 'true');
    expect(screen.getAllByRole('checkbox').filter((s) => s.getAttribute('tabindex') === '0')).toHaveLength(0);
    fireEvent.click(seat(/Main hall, row A, seat 1/));
    expect(onToggle).not.toHaveBeenCalled();
  });

  it('draws a venue with 2,000 seats', () => {
    const big = makeLayout([{ name: 'Arena', rows: 20, seatsPerRow: 100 }]);
    render(<SeatMap layout={big} unavailable={new Set(['s0-A-1'])} selected={new Set()} interactive onToggle={() => {}} />);
    const map = screen.getByRole('group', { name: 'Seat map' });
    expect(within(map).getAllByRole('checkbox')).toHaveLength(2000);
  });
});
