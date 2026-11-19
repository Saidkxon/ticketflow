import { useQuery } from '@tanstack/react-query';
import { getMyLayout } from '../api/organizer';
import { useSessionStore } from '../auth/SessionContext';

/** The organizer's layout of one event. The editor, the publish panel and the summary share this one query. */
export function useMyLayout(eventId: string) {
  const store = useSessionStore();
  return useQuery({
    queryKey: ['my-layout', eventId],
    queryFn: () => getMyLayout((input, init) => store.authorizedFetch(input, init), eventId),
  });
}
