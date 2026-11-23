import { Route, Routes } from 'react-router';
import { AccountPage } from './pages/AccountPage';
import { EventPage } from './pages/EventPage';
import { AdminLocationsPage } from './pages/AdminLocationsPage';
import { HomePage } from './pages/HomePage';
import { LoginPage } from './pages/LoginPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { OrganizerEventFormPage } from './pages/OrganizerEventFormPage';
import { OrganizerEventsPage } from './pages/OrganizerEventsPage';
import { RegisterPage } from './pages/RegisterPage';
import { RequireAuth } from './auth/RequireAuth';
import { Layout } from './Layout';
import { AdminArea } from './admin/RequireAdmin';
import { OrganizerArea } from './organizer/RequireOrganizer';

export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<HomePage />} />
        <Route path="events/:eventId" element={<EventPage />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="register" element={<RegisterPage />} />
        <Route
          path="account"
          element={
            <RequireAuth>
              <AccountPage />
            </RequireAuth>
          }
        />
        <Route path="admin" element={<AdminArea />}>
          <Route path="locations" element={<AdminLocationsPage />} />
        </Route>
        <Route path="organizer" element={<OrganizerArea />}>
          <Route index element={<OrganizerEventsPage />} />
          <Route path="events/new" element={<OrganizerEventFormPage />} />
          <Route path="events/:eventId" element={<OrganizerEventFormPage />} />
        </Route>
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}
