// Playlist consumption for the cloud viewer: same /__playlist__/ contract as
// the media + model viewers, accepting only point_cloud items. A missing
// playlist is the normal single-cloud case, never an error.

import { sourceFileName } from "./loaders.js";

const PLAYLIST_ROUTE = "/__playlist__/";

export function normalizeCloudItem(raw) {
  if (!raw || typeof raw.src !== "string" || !raw.src) return null;
  if (String(raw.type || "").toLowerCase() !== "point_cloud") return null;
  return {
    id: raw.id || "",
    title: raw.title || sourceFileName(raw.src),
    src: raw.src,
    badge: raw.badge || "POINT CLOUD",
    description: raw.description || "",
    thumb: raw.thumb || "",
  };
}

export async function fetchCloudPlaylist(config) {
  if (!config.playlist || config.demo) return null;
  try {
    const res = await fetch(PLAYLIST_ROUTE + encodeURIComponent(config.playlist));
    if (!res.ok) return null;
    const doc = await res.json();
    if (!doc || doc.version !== 1 || !Array.isArray(doc.items)) return null;
    return adoptCloudPlaylist(doc, config);
  } catch (e) {
    console.warn("[cloud] playlist unavailable: " + (e && e.message ? e.message : e));
    return null;
  }
}

export function adoptCloudPlaylist(doc, config) {
  const items = doc.items.map(normalizeCloudItem).filter(Boolean);
  if (items.length < 2) return null;
  // Reconcile the displayed item: id, then URL index, then doc index, then src.
  let index = config.id ? items.findIndex((it) => it.id && it.id === config.id) : -1;
  if (index < 0 && Number.isFinite(config.playlistIndex)) {
    index = Math.max(0, Math.min(items.length - 1, config.playlistIndex));
  }
  if (index < 0 && Number.isFinite(doc.index)) {
    index = Math.max(0, Math.min(items.length - 1, doc.index));
  }
  if (index < 0) index = Math.max(0, items.findIndex((it) => it.src === config.src));
  return { items, index };
}
