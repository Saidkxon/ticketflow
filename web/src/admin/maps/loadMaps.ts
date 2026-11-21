/**
 * The only place that knows which map library is used (ADR-0015, L9): Leaflet with OpenStreetMap tiles. The rest of the
 * app uses LocationMap, which talks to the small MapsApi interface below, so another provider (Google Maps, for
 * example) could replace this file without touching the screens.
 *
 * Leaflet is loaded on demand, so only the administrator page pays for it.
 */

export interface LatLng {
  lat: number;
  lng: number;
}

export interface BoundsInstance {
  extend(point: LatLng): void;
}

export interface MapInstance {
  fitBounds(bounds: BoundsInstance, padding?: number): void;
  setCenter(center: LatLng): void;
  setZoom(zoom: number): void;
  /** Releases the map, so the container can be used again. */
  destroy(): void;
}

export interface MarkerInstance {
  remove(): void;
  onClick(handler: () => void): void;
  /** Shows a window next to the marker. The content is built from text nodes by the caller, never from HTML. */
  openPopup(content: HTMLElement): void;
}

export interface PolylineInstance {
  remove(): void;
}

export interface MarkerOptions {
  map: MapInstance;
  position: LatLng;
  /** The name of the person. Shown next to the pin, or as hover text on a small dot. */
  title: string;
  /** One letter shown on the pin. */
  label?: string;
  /** A small dot instead of a pin with a name, used for the points of a history. */
  small?: boolean;
}

export interface MapsApi {
  createMap(element: HTMLElement, center: LatLng, zoom: number): MapInstance;
  createBounds(): BoundsInstance;
  createMarker(options: MarkerOptions): MarkerInstance;
  createPolyline(map: MapInstance, path: LatLng[]): PolylineInstance;
}

const ATTRIBUTION = '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors';

function textElement(tag: string, text: string, className?: string): HTMLElement {
  const element = document.createElement(tag);
  element.textContent = text; // names are typed by users: text only, never HTML
  if (className) {
    element.className = className;
  }
  return element;
}

let loading: Promise<MapsApi> | null = null;

export function loadMaps(): Promise<MapsApi> {
  loading ??= import('leaflet').then((leafletModule) => {
    const L = leafletModule.default ?? leafletModule;
    const raw = new WeakMap<object, import('leaflet').Map>();

    const leafletOf = (map: MapInstance) => raw.get(map)!;

    const api: MapsApi = {
      createMap(element, center, zoom) {
        const map = L.map(element).setView([center.lat, center.lng], zoom);
        L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: ATTRIBUTION }).addTo(map);
        const instance: MapInstance = {
          fitBounds: (bounds, padding = 0) => {
            const leafletBounds = L.latLngBounds([]);
            (bounds as unknown as { points: LatLng[] }).points.forEach((point) => leafletBounds.extend([point.lat, point.lng]));
            map.fitBounds(leafletBounds, { padding: [padding, padding] });
          },
          setCenter: (point) => void map.setView([point.lat, point.lng], map.getZoom()),
          setZoom: (value) => void map.setZoom(value),
          destroy: () => void map.remove(),
        };
        raw.set(instance, map);
        return instance;
      },
      createBounds() {
        const points: LatLng[] = [];
        return { points, extend: (point: LatLng) => void points.push(point) } as BoundsInstance;
      },
      createMarker({ map, position, title, label, small }) {
        const latLng: [number, number] = [position.lat, position.lng];
        const marker = small
          ? L.circleMarker(latLng, { radius: 4, weight: 1, fillOpacity: 1 })
          : L.marker(latLng, {
              icon: L.divIcon({
                className: 'map-pin',
                html: textElement('span', label ?? '?'),
                iconSize: [28, 28],
                iconAnchor: [14, 14],
              }),
              title,
            });
        marker.bindTooltip(textElement('span', title), { permanent: !small, direction: 'right', offset: [14, 0] });
        marker.addTo(leafletOf(map));
        return {
          remove: () => void marker.remove(),
          onClick: (handler) => void marker.on('click', handler),
          openPopup: (content) => void marker.bindPopup(content).openPopup(),
        };
      },
      createPolyline(map, path) {
        const line = L.polyline(path.map((point): [number, number] => [point.lat, point.lng]), { weight: 3, opacity: 0.8 });
        line.addTo(leafletOf(map));
        return { remove: () => void line.remove() };
      },
    };
    return api;
  });
  loading.catch(() => {
    loading = null; // the next visit tries again
  });
  return loading;
}
