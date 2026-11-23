import { afterEach, describe, expect, it, vi } from 'vitest';
import { loadSelection, saveSelection } from './selectionStorage';

afterEach(() => {
  vi.restoreAllMocks();
});

describe('selectionStorage', () => {
  it('keeps the seats of one event apart from another', () => {
    saveSelection('e1', ['a', 'b']);
    saveSelection('e2', ['c']);
    expect(loadSelection('e1')).toEqual(['a', 'b']);
    expect(loadSelection('e2')).toEqual(['c']);
    expect(loadSelection('e3')).toEqual([]);
  });

  it('removes the entry when nothing is chosen', () => {
    saveSelection('e1', ['a']);
    saveSelection('e1', []);
    expect(window.sessionStorage.getItem('ticketflow.selection.e1')).toBeNull();
  });

  it('ignores what it cannot understand and never keeps more than eight seats', () => {
    window.sessionStorage.setItem('ticketflow.selection.e1', '{"not":"a list"}');
    expect(loadSelection('e1')).toEqual([]);
    window.sessionStorage.setItem('ticketflow.selection.e1', 'not json');
    expect(loadSelection('e1')).toEqual([]);
    window.sessionStorage.setItem('ticketflow.selection.e1', JSON.stringify(['a', 1, null, 'b']));
    expect(loadSelection('e1')).toEqual(['a', 'b']);
    window.sessionStorage.setItem('ticketflow.selection.e1', JSON.stringify(Array.from({ length: 20 }, (_, i) => `s${i}`)));
    expect(loadSelection('e1')).toHaveLength(8);
  });

  it('does not fail when the storage throws', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota');
    });
    expect(() => saveSelection('e1', ['a'])).not.toThrow();
  });
});
