package com.ticketflow.catalog.events;

/** A layout together with the event version it belongs to, which goes out as the ETag. */
record VersionedLayout(LayoutDetail layout, long version) {
}
