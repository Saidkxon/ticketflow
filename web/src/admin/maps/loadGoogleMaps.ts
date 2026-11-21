/**
 * The only place that knows how Google Maps is loaded (ADR-0015, L9). The rest of the app uses LocationMap.
 * The types below describe just the parts of the Google Maps JavaScript API that we use, so no extra package is needed.
 */

export interface LatLng {
  lat: number;
  lng: number;
}

export interface MapInstance {
  fitBounds(bounds: BoundsInstance, padding?: number): void;
  setCenter(center: LatLng): void;
  setZoom(zoom: number): void;
}

export interface BoundsInstance {
  extend(point: LatLng): void;
}

export interface MarkerInstance {
  setMap(map: MapInstance | null): void;
  addListener(event: 'click', handler: () => void): void;
}

export interface InfoWindowInstance {
  setContent(content: HTMLElement): void;
  open(options: { map: MapInstance; anchor: MarkerInstance }): void;
  close(): void;
}

export interface PolylineInstance {
  setMap(map: MapInstance | null): void;
}

export interface MarkerOptions {
  map: MapInstance;
  position: LatLng;
  title?: string;
  label?: string;
  /** A small circle instead of the pin, used for history points. */
  small?: boolean;
}

export interface MapsApi {
  createMap(element: HTMLElement, center: LatLng, zoom: number): MapInstance;
  createBounds(): BoundsInstance;
  createMarker(options: MarkerOptions): MarkerInstance;
  createInfoWindow(): InfoWindowInstance;
  createPolyline(map: MapInstance, path: LatLng[]): PolylineInstance;
}

/** The slice of the global `google.maps` object that we touch. */
interface GoogleMapsGlobal {
  Map: new (element: HTMLElement, options: object) => MapInstance;
  LatLngBounds: new () => BoundsInstance;
  Marker: new (options: object) => MarkerInstance;
  InfoWindow: new () => InfoWindowInstance;
  Polyline: new (options: object) => PolylineInstance;
  SymbolPath: { CIRCLE: number };
}

declare global {
  interface Window {
    google?: { maps?: GoogleMapsGlobal };
    __ticketflowMapsReady?: () => void;
    gm_authFailure?: () => void;
  }
}

const authFailureListeners = new Set<() => void>();

/** Google calls this when the key is wrong, not allowed for this address, or over its quota. */
export function onMapsAuthFailure(listener: () => void): () => void {
  authFailureListeners.add(listener);
  return () => {
    authFailureListeners.delete(listener);
  };
}

function wrap(maps: GoogleMapsGlobal): MapsApi {
  return {
    createMap: (element, center, zoom) =>
      new maps.Map(element, { center, zoom, streetViewControl: false, mapTypeControl: false }),
    createBounds: () => new maps.LatLngBounds(),
    createMarker: ({ map, position, title, label, small }) =>
      new maps.Marker({
        map,
        position,
        title,
        label,
        icon: small
          ? { path: maps.SymbolPath.CIRCLE, scale: 4, fillColor: '#1a73e8', fillOpacity: 1, strokeWeight: 1 }
          : undefined,
      }),
    createInfoWindow: () => new maps.InfoWindow(),
    createPolyline: (map, path) =>
      new maps.Polyline({ map, path, strokeColor: '#1a73e8', strokeOpacity: 0.8, strokeWeight: 3 }),
  };
}

let loading: Promise<MapsApi> | null = null;

/** Loads the Google Maps script once. The key is a browser key: it is restricted to our site addresses in the Google console. */
export function loadGoogleMaps(apiKey: string): Promise<MapsApi> {
  loading ??= new Promise<MapsApi>((resolve, reject) => {
    window.gm_authFailure = () => authFailureListeners.forEach((listener) => listener());
    window.__ticketflowMapsReady = () => {
      const maps = window.google?.maps;
      if (maps) {
        resolve(wrap(maps));
      } else {
        loading = null;
        reject(new Error('Google Maps did not start'));
      }
    };
    const script = document.createElement('script');
    script.src =
      'https://maps.googleapis.com/maps/api/js?key=' +
      encodeURIComponent(apiKey) +
      '&callback=__ticketflowMapsReady&v=weekly';
    script.async = true;
    script.onerror = () => {
      loading = null; // the next visit tries again
      reject(new Error('Google Maps could not be loaded'));
    };
    document.head.append(script);
  });
  return loading;
}
