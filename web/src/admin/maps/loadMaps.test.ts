import { afterEach, describe, expect, it } from 'vitest';
import { loadMaps } from './loadMaps';

// These tests run the real Leaflet in jsdom (no network: the tile images are never fetched here).
const TRAP = '<img src=x onerror=alert(1)>';

let container: HTMLElement;

afterEach(() => container?.remove());

function newContainer() {
  container = document.createElement('div');
  container.style.width = '400px';
  container.style.height = '300px';
  document.body.append(container);
  return container;
}

describe('the Leaflet adapter', () => {
  it('draws a named pin, a trail and a popup from text only, and cleans up', async () => {
    const api = await loadMaps();
    const map = api.createMap(newContainer(), { lat: 41.3, lng: 69.2 }, 11);

    const marker = api.createMarker({ map, position: { lat: 41.31, lng: 69.28 }, title: TRAP, label: 'G' });
    const dot = api.createMarker({ map, position: { lat: 41.32, lng: 69.29 }, title: 'Grace', small: true });
    const line = api.createPolyline(map, [
      { lat: 41.31, lng: 69.28 },
      { lat: 41.32, lng: 69.29 },
    ]);

    expect(container.querySelector('.leaflet-tile-pane')).not.toBeNull();
    expect(container.querySelector('.map-pin span')?.textContent).toBe('G');
    expect(container.querySelector('.leaflet-tooltip')?.textContent).toBe(TRAP);
    expect(container.querySelector('img[src="x"]')).toBeNull();
    expect(container.querySelector('.leaflet-overlay-pane path')).not.toBeNull();

    const content = document.createElement('div');
    content.textContent = TRAP;
    marker.openPopup(content);
    expect(container.querySelector('.leaflet-popup-content')?.textContent).toBe(TRAP);
    expect(container.querySelector('.leaflet-popup-content img')).toBeNull();

    const bounds = api.createBounds();
    bounds.extend({ lat: 41.31, lng: 69.28 });
    bounds.extend({ lat: 41.32, lng: 69.29 });
    map.fitBounds(bounds, 40);
    map.setCenter({ lat: 41.31, lng: 69.28 });
    map.setZoom(15);

    marker.remove();
    dot.remove();
    line.remove();
    expect(container.querySelector('.map-pin')).toBeNull();
    map.destroy();
    // The container can be used again after destroy (React may run an effect twice in development).
    const again = api.createMap(container, { lat: 41.3, lng: 69.2 }, 11);
    again.destroy();
  });

  it('loads the library only once', async () => {
    expect(await loadMaps()).toBe(await loadMaps());
  });
});
