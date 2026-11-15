/** Where to go after signing in: the page the person wanted, if it is a path inside this app. Otherwise the home page. */
export function returnPathFrom(state: unknown): string {
  const from = (state as { from?: unknown } | null)?.from;
  if (typeof from === 'string' && from.startsWith('/') && !from.startsWith('//')) {
    return from;
  }
  return '/';
}
