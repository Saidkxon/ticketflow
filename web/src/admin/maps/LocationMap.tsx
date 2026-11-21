import 'leaflet/dist/leaflet.css';
import { useEffect, useRef, useState } from 'react';
import { loadMaps, type LatLng, type MapInstance, type MapsApi } from './loadMaps';

export interface MapPoint {
  id: string;
  position: LatLng;
  /** The name shown on the marker. */
  title: string;
  /** Lines shown when the marker is clicked. Shown as plain text, never as HTML. */
  details: string[];
}

interface Props {
  points: MapPoint[];
  /** When set, the points are one user's history (drawn as a line and small dots) instead of people. */
  trail?: boolean;
}

const TASHKENT: LatLng = { lat: 41.3111, lng: 69.2797 };

/** One map with markers (OpenStreetMap). The only component that uses the map library. */
export function LocationMap({ points, trail = false }: Props) {
  const container = useRef<HTMLDivElement>(null);
  const [api, setApi] = useState<MapsApi | null>(null);
  const [failed, setFailed] = useState(false);
  const mapRef = useRef<MapInstance | null>(null);
  const [mapReady, setMapReady] = useState(false);

  // Load the map library once.
  useEffect(() => {
    let cancelled = false;
    loadMaps().then(
      (loaded) => {
        if (!cancelled) {
          setApi(loaded);
        }
      },
      () => {
        if (!cancelled) {
          setFailed(true);
        }
      },
    );
    return () => {
      cancelled = true;
    };
  }, []);

  // Create the map when the library and the container are ready.
  useEffect(() => {
    if (api === null || container.current === null) {
      return;
    }
    const map = api.createMap(container.current, TASHKENT, 11);
    mapRef.current = map;
    setMapReady(true);
    return () => {
      mapRef.current = null;
      setMapReady(false);
      map.destroy();
    };
  }, [api]);

  // Draw the points again whenever they change.
  useEffect(() => {
    const map = mapRef.current;
    if (api === null || map === null || !mapReady) {
      return;
    }
    const bounds = api.createBounds();
    const drawn: { remove(): void }[] = [];
    for (const point of points) {
      bounds.extend(point.position);
      const marker = api.createMarker({
        map,
        position: point.position,
        title: point.title,
        label: trail ? undefined : point.title.trim().charAt(0).toUpperCase() || undefined,
        small: trail,
      });
      marker.onClick(() => {
        const content = document.createElement('div');
        for (const [index, line] of [point.title, ...point.details].entries()) {
          const element = document.createElement(index === 0 ? 'strong' : 'div');
          element.textContent = line; // names are typed by users: text only, never HTML
          content.append(element);
        }
        marker.openPopup(content);
      });
      drawn.push(marker);
    }
    if (trail && points.length > 1) {
      drawn.push(api.createPolyline(map, [...points].reverse().map((point) => point.position)));
    }
    if (points.length === 1) {
      map.setCenter(points[0].position);
      map.setZoom(15);
    } else if (points.length > 1) {
      map.fitBounds(bounds, 40);
    }
    return () => drawn.forEach((item) => item.remove());
  }, [api, mapReady, points, trail]);

  if (failed) {
    return (
      <p role="alert">
        The map could not be loaded. The table below still works.
      </p>
    );
  }
  return <div ref={container} className="map" role="region" aria-label="Map of user positions" />;
}
