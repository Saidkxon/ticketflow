import { useQuery } from '@tanstack/react-query';
import { getConsent } from '../api/location';
import { useSessionState, useSessionStore } from '../auth/SessionContext';

/** The query key includes the user, so one person's answer is never shown to the next person on the same browser. */
export function consentQueryKey(userId: string) {
  return ['location-consent', userId] as const;
}

export function useConsent() {
  const session = useSessionState();
  const store = useSessionStore();
  const userId = session.status === 'signedIn' ? session.user.id : '';
  const query = useQuery({
    queryKey: consentQueryKey(userId),
    queryFn: () => getConsent(store.authorizedFetch),
    enabled: userId !== '',
  });
  return { userId, query };
}
