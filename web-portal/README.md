# MS Bank — Customer Web Portal

A React + Vite customer portal for the **microservice-bank** demo. All API calls go through the API Gateway (`/api/v1/*` and `/bff/v1/*`).

## Features

- Email/password login + registration (12+ char rule)
- Memory-only access token + refresh-token persistence in browser storage
- Client-rendered dashboard with total balance, balance-trend chart, accounts list, recent transfers, unread notifications badge
- Account detail with paged transaction history (TanStack Query infinite query)
- 4-step transfer wizard with client-generated `Idempotency-Key` and status polling
- Notifications inbox with channel/status filters
- Security settings: change password + TOTP MFA enrollment (QR code)
- Responsive top + side nav (collapses below `md`), dark mode toggle (Tailwind `class` strategy)
- Accessible: labels, focus rings, ARIA on dialogs, semantic HTML
- Brand: "MS Bank" — slate + emerald

## Stack

- **React 18 + Vite + React Router**, TypeScript strict
- **TailwindCSS** + hand-built UI primitives (`components/ui/`)
- **TanStack Query v5** for client data fetching
- **react-hook-form** + **zod** for forms/validation
- **recharts** for the balance trend chart
- **lucide-react** for icons
- **qrcode.react** for MFA QR
- **vitest** + **@testing-library/react** for unit tests
- **Playwright** for the e2e smoke test

## Project layout

```
web-portal/
├── src/
│   ├── App.tsx                         (route definitions)
│   ├── Providers.tsx                   (React Query + Auth providers)
│   ├── layouts/{AppShell,AuthShell}.tsx
│   ├── pages/
│   │   ├── {LoginPage,RegisterPage,DashboardPage}.tsx
│   │   ├── {AccountDetailPage,TransferPage}.tsx
│   │   ├── {NotificationsPage,SecurityPage,NotFoundPage}.tsx
│   └── main.tsx
├── components/{ui,dashboard,accounts,transfers,notifications,settings,nav}
├── lib/
│   ├── api/{client,endpoints,types}.ts
│   ├── auth/AuthProvider.tsx
│   ├── format/{money,date}.ts
│   ├── config.ts, queryClient.ts, useTheme.ts, cn.ts
├── styles/globals.css
├── tests/{unit/format.test.ts, e2e/smoke.spec.ts}
├── Dockerfile, vite.config.ts, tailwind.config.ts, tsconfig.json
└── package.json
```

## Run

```bash
# 1. Configure
cp .env.example .env.local
# Edit if needed:
#   VITE_API_BASE_URL=http://localhost:8080

# 2. Install
npm install

# 3. Dev server
npm run dev                # http://localhost:3000

# 4. Production build
npm run build
npm start

# 5. Quality gates
npm run typecheck
npm run lint
npm test                   # vitest unit tests
npm run test:e2e           # Playwright (skips gracefully if portal/gateway down)
```

### Environment

| Variable                  | Where        | Purpose                                       |
| ------------------------- | ------------ | --------------------------------------------- |
| `VITE_API_BASE_URL`       | client       | Base URL used by browser-side fetches         |
| `E2E_BASE_URL`            | playwright   | Defaults to `http://localhost:3000`           |
| `E2E_EMAIL`/`E2E_PASSWORD`| playwright   | Demo credentials for the smoke test           |
| `E2E_DESTINATION`         | playwright   | Destination account UUID for the smoke test   |

## Auth flow

1. `POST /api/v1/auth/login` is called directly from the browser to the API Gateway.
2. Access token is stored in memory (`AuthProvider`), and refresh token is persisted for session restore.
3. Client `fetcher` automatically calls `/api/v1/auth/refresh` on a 401 and retries once.
4. On refresh failure, auth state is cleared and the user is redirected to `/login`.

## Money

`formatMoney(amountMinorUnits, currency)` uses `Intl.NumberFormat`. All amounts in transit are integer minor units; conversion happens only at the input boundary via `majorToMinor`.

## Docker

```bash
docker build -t msbank-web-portal .
docker run --rm -p 3000:3000 \
  -e VITE_API_BASE_URL=http://host.docker.internal:8080 \
  msbank-web-portal
```

Image is multi-stage (`node:20-alpine` builder → `nginx:alpine` runner) and serves the built SPA with fallback routing.

## Screenshots

_Placeholders — capture after first run:_

- `docs/screenshot-login.png`
- `docs/screenshot-dashboard.png`
- `docs/screenshot-transfer-wizard.png`

## Deviations / notes

- The OpenAPI spec doesn't define a "change password" endpoint, so the form is wired client-side only and shows a demo success message.
- The balance trend chart synthesizes 30 days of data around the current total — the spec doesn't expose a history endpoint.
- The accounts service doesn't list a transactions-per-account endpoint; the `Account` detail page uses `GET /api/v1/transfers?accountId=…` as the closest match.
- `GET /api/v1/accounts/{id}` is referenced via the gateway but not enumerated in `gateway.yaml`; we assume the gateway proxies the accounts-service path.
