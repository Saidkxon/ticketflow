# ADR-0010: Frontend stack, design system and seat-map rendering

| | |
|---|---|
| **Status** | Accepted (decision delegated to the architect; acknowledged by the instructor on 2026-11-01 |
| **Date** | 2026-11-01 |
| **Deciders** | Claude (architect); Saidafzal (instructor) acknowledges |

## Context

The charter puts a polished, responsive web app in scope (FR-13 to FR-18, NFR-9, NFR-10). The hardest UI element is an **interactive seat map** for venues of about 2,000 specific seats, with live availability and a hold countdown. The developer's main strength is Java backend, and the frontend shares a 10-15 hours per week budget with everything else.

## Decision drivers

1. Quality of result: a clean, responsive, accessible UI with a smooth seat map
2. Learning value and employability: a stack widely used in industry
3. Keeping the team small: as few moving parts as possible
4. Fit with a separate API gateway and independently deployed services
5. Large ecosystem so common problems already have solutions

## Options considered

### Option A: React + TypeScript + Vite (single-page app)
- Pros: most widely used UI stack; huge ecosystem; good fit for interactive SVG; static hosting is trivial; strong typing from TypeScript helps a backend-minded developer
- Cons: new language and toolchain to learn; no server-side rendering by default

### Option B: Next.js (React with server rendering)
- Pros: server-side rendering helps search engine visibility of event pages; routing and data patterns built in
- Cons: adds a Node server to run and a second "backend" that blurs service boundaries; more concepts for little MVP value

### Option C: Angular
- Pros: opinionated structure that Java developers often find familiar; batteries included
- Cons: heavier framework; smaller choice of seat-map and UI building blocks; steeper ceremony for a one-person project

### Option D: Java-based UI (Thymeleaf or Vaadin)
- Pros: stays in Java
- Cons: poor fit for a highly interactive seat map and a polished modern UI; weaker transferable skills

## Decision

**Option A: React + TypeScript, built with Vite, as a single-page app in `web/`.**

Supporting choices:

- **Routing and server state:** React Router and TanStack Query (caching, retries, background refresh).
- **Styling and design system:** Tailwind CSS with accessible, unstyled primitives (shadcn/ui on top of Radix). A small TicketFlow design system (colors, typography, spacing, components) is defined during Phase 1 wireframing and lives in `web/`.
- **Seat map rendering:** **SVG**, one element per seat, with pan and zoom. SVG gives crisp scaling, easy hit-testing, CSS styling per seat state and good accessibility for about 2,000 seats (NFR-10). If measurements show it is not smooth enough, we switch the map layer to Canvas behind the same component interface.
- **Testing:** Vitest and Testing Library for components, Playwright for end-to-end flows.
- **Quality gates in CI:** type check, lint, format check, unit tests (details in Phase 2).
- Exact library versions are pinned when `web/` is scaffolded, after checking what is current.
- Server-side rendering is **not** needed for the MVP. If search visibility of public event pages becomes a goal, we revisit (see below).

## Consequences

**Positive**
- Industry-standard skills, abundant examples and components
- Frontend deploys as static files, independent of the backend
- The seat map stays a self-contained component that can change rendering technology later

**Negative / risks**
- The developer must learn TypeScript and the React toolchain while also learning microservices. Mitigation: the frontend is built in thin slices alongside each backend slice, and the architect reviews UI code with the same rigor as backend code.
- A pure SPA has weaker SEO. Acceptable for the MVP (non-goal), revisit if needed.
- Security: tokens in the browser need a careful design. Mitigation: decided in ADR-0004 before login is built.

## Revisit when

- SEO or share-link previews for event pages become a requirement (consider Next.js or pre-rendering)
- Seat-map performance misses NFR-10 with SVG (consider Canvas)
- Real-time updates (ADR-0011) require a different client data strategy

## References

- Charter v1.0: FR-13 to FR-18, NFR-9, NFR-10, risk "UI doubles the workload"
- ADR-0004 (authentication), ADR-0011 (real-time availability)
