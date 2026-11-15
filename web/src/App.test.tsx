import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { ApiError } from './api/problem';
import { SessionProvider } from './auth/SessionContext';
import { createSessionStore } from './auth/sessionStore';
import { ADA, fakeApi, token, type FakeApi } from './test/fakeApi';

beforeEach(() => {
  // The home page lists events; an empty list is enough here.
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation(() =>
      Promise.resolve(
        new Response(JSON.stringify({ items: [], page: 0, size: 20, totalItems: 0 }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    ),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function renderApp(api: FakeApi, path = '/') {
  const store = createSessionStore(api);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <SessionProvider store={store}>
          <App />
        </SessionProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return store;
}

function type(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

function signInForm(email = 'ada@example.com', password = 'a long password') {
  type('Email', email);
  type('Password', password);
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
}

describe('the header', () => {
  it('offers sign in and registration to a visitor', async () => {
    renderApp(fakeApi());

    expect(await screen.findByRole('link', { name: 'Sign in' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Create account' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sign out' })).not.toBeInTheDocument();
  });

  it('shows the name and a sign out button after the session is restored from the cookie', async () => {
    const api = fakeApi();
    api.refresh.mockResolvedValue(token('access-1'));

    renderApp(api);

    expect(await screen.findByRole('link', { name: 'Ada Lovelace' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
  });

  it('signs out', async () => {
    const api = fakeApi();
    api.refresh.mockResolvedValue(token('access-1'));
    renderApp(api);

    fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }));

    expect(await screen.findByRole('link', { name: 'Sign in' })).toBeInTheDocument();
    expect(api.logout).toHaveBeenCalledTimes(1);
  });
});

describe('the sign in page', () => {
  it('signs in and goes to the home page', async () => {
    const api = fakeApi();
    renderApp(api, '/login');

    await screen.findByRole('heading', { name: 'Sign in' });
    signInForm();

    expect(await screen.findByRole('heading', { name: 'Upcoming events' })).toBeInTheDocument();
    expect(api.login).toHaveBeenCalledWith({ email: 'ada@example.com', password: 'a long password' });
  });

  it('says plainly when the email or password is not right, without a reference', async () => {
    const api = fakeApi();
    api.login.mockRejectedValue(new ApiError(401, 'INVALID_CREDENTIALS', 'ref-123'));
    renderApp(api, '/login');

    await screen.findByRole('heading', { name: 'Sign in' });
    signInForm();

    expect(await screen.findByRole('alert')).toHaveTextContent('The email or password is not right.');
    expect(screen.queryByText(/Reference:/)).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('shows the reference when something unexpected went wrong', async () => {
    const api = fakeApi();
    api.login.mockRejectedValue(new ApiError(500, 'INTERNAL_ERROR', 'ref-500'));
    renderApp(api, '/login');

    await screen.findByRole('heading', { name: 'Sign in' });
    signInForm();

    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong');
    expect(screen.getByText(/Reference: ref-500/)).toBeInTheDocument();
  });

  it('asks the person to wait when there were too many attempts', async () => {
    const api = fakeApi();
    api.login.mockRejectedValue(new ApiError(429, 'RATE_LIMITED'));
    renderApp(api, '/login');

    await screen.findByRole('heading', { name: 'Sign in' });
    signInForm();

    expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts');
  });

  it('keeps the button off while the request runs', async () => {
    const api = fakeApi();
    api.login.mockReturnValue(new Promise(() => {}));
    renderApp(api, '/login');

    await screen.findByRole('heading', { name: 'Sign in' });
    signInForm();

    await waitFor(() => expect(screen.getByRole('button', { name: 'Sign in' })).toBeDisabled());
  });

  it('sends a signed-in user to the home page instead', async () => {
    const api = fakeApi();
    api.refresh.mockResolvedValue(token('access-1'));

    renderApp(api, '/login');

    expect(await screen.findByRole('heading', { name: 'Upcoming events' })).toBeInTheDocument();
  });
});

describe('the registration page', () => {
  function fill() {
    type('Name', 'Ada Lovelace');
    type('Email', 'ada@example.com');
    type('Password', 'a long password');
    fireEvent.click(screen.getByRole('button', { name: 'Create account' }));
  }

  it('registers, signs in and goes to the home page', async () => {
    const api = fakeApi();
    renderApp(api, '/register');

    await screen.findByRole('heading', { name: 'Create an account' });
    fill();

    expect(await screen.findByRole('heading', { name: 'Upcoming events' })).toBeInTheDocument();
    expect(api.register).toHaveBeenCalledWith({
      email: 'ada@example.com',
      password: 'a long password',
      displayName: 'Ada Lovelace',
    });
    expect(screen.getByRole('link', { name: ADA.displayName })).toBeInTheDocument();
  });

  it('shows what the server did not accept next to the field', async () => {
    const api = fakeApi();
    api.register.mockRejectedValue(
      new ApiError(400, 'INVALID_REQUEST', 'ref-9', [{ field: 'password', problem: 'is too common' }]),
    );
    renderApp(api, '/register');

    await screen.findByRole('heading', { name: 'Create an account' });
    fill();

    expect(await screen.findByText('Password is too common')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Please check the fields below.');
  });

  it('tells a person whose email is taken to sign in', async () => {
    const api = fakeApi();
    api.register.mockRejectedValue(new ApiError(409, 'EMAIL_ALREADY_REGISTERED'));
    renderApp(api, '/register');

    await screen.findByRole('heading', { name: 'Create an account' });
    fill();

    expect(await screen.findByRole('alert')).toHaveTextContent('already exists');
  });

  it('states the password rules from the contract', async () => {
    renderApp(fakeApi(), '/register');

    expect(await screen.findByText('10 to 72 characters.')).toBeInTheDocument();
    expect(screen.getByLabelText('Password')).toHaveAttribute('minlength', '10');
    expect(screen.getByLabelText('Password')).toHaveAttribute('maxlength', '72');
  });
});

describe('the account page', () => {
  it('sends a visitor to the sign in page and back after signing in', async () => {
    const api = fakeApi();
    renderApp(api, '/account');

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
    signInForm();

    expect(await screen.findByRole('heading', { name: 'Your account' })).toBeInTheDocument();
    expect(screen.getByText('ada@example.com')).toBeInTheDocument();
    expect(screen.getByText('CUSTOMER')).toBeInTheDocument();
  });

  it('shows a signed-in user their account', async () => {
    const api = fakeApi();
    api.refresh.mockResolvedValue(token('access-1'));

    renderApp(api, '/account');

    expect(await screen.findByRole('heading', { name: 'Your account' })).toBeInTheDocument();
  });

  it('waits while it looks for a session instead of redirecting at once', async () => {
    const api = fakeApi();
    api.refresh.mockReturnValue(new Promise(() => {}));

    renderApp(api, '/account');

    expect(await screen.findByRole('status')).toHaveTextContent('Checking your session');
    expect(screen.queryByRole('heading', { name: 'Sign in' })).not.toBeInTheDocument();
  });
});

describe('other pages', () => {
  it('shows a not found page for an unknown path', async () => {
    renderApp(fakeApi(), '/nothing-here');

    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
  });
});
