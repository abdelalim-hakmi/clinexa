# Clinexa Client

Angular 22 single-page application for the Clinexa platform.

## Prerequisites

| Requirement | Version | Command to Check |
|-------------|---------|------------------|
| **Node.js** | ^22.22.3 \|\| ^24.15.0 \|\| >=26.0.0 | `node --version` |
| **Yarn** | 4.12.0+ | `yarn --version` |

Verify your setup:
```bash
node --version   # Should satisfy ^22.22.3 || ^24.15.0 || >=26.0.0 (required by Angular 22)
yarn --version   # Should be 4.12.0 or higher
```

## Installation

From the `client/` directory:

```bash
yarn install
```

This installs all dependencies using Yarn 4 Berry (with `node-modules` linker).

## Development Server

Start the development server:

```bash
yarn start
```

Or using Angular CLI directly:

```bash
ng serve
```

The application will be available at **http://localhost:4200/**

Hot reload is enabled — changes to files will automatically refresh the browser.

## Building for Production

Build the optimized production bundle:

```bash
yarn build
```

Or:

```bash
ng build
```

Output is generated in the `dist/` directory.

## Testing

Run unit tests via Vitest:

```bash
yarn test
```

Or:

```bash
ng test
```

**Note**: There is no e2e test setup yet.

## Generating Components

Create a new component:

```bash
ng generate component components/MyComponent
```

- **Default style format**: SCSS (configured in `angular.json`)
- **Note**: Initial app files use `.css`; new components will use `.scss` by default

## Project Layout

```
client/
├── README.md              # You are here
├── package.json           # Dependencies & scripts
├── angular.json           # Angular CLI configuration
├── tsconfig.json          # TypeScript configuration
├── .yarnrc.yml            # Yarn configuration (node-modules linker)
├── proxy.conf.json        # Dev proxy: /api → api-gateway (:9000) — see the warning below
├── src/
│   ├── main.ts            # Application entry point
│   ├── styles.css         # Global styles
│   ├── index.html         # HTML shell
│   └── app/
│       ├── app.config.ts  # Providers — router, HttpClient, XSRF
│       ├── app.routes.ts  # /login (public) and / (behind authGuard)
│       ├── auth/          # The only place that talks about authentication
│       │   ├── auth.service.ts    # Login, logout, "who am I" — stores no token
│       │   ├── auth.guard.ts      # Protects the EXPERIENCE, never the data
│       │   ├── auth.models.ts     # Me, ClinicAssignment, Problem (RFC 7807)
│       │   └── login/             # The login screen
│       └── home/          # Post-login screen: identity + clinics and roles
└── dist/                  # Production build (generated)
```

### Two rules the front-end does not get to bend

**The guard protects the experience, not the data.** `auth.guard.ts` keeps a signed-out visitor from
watching a screen fill up with `401`s. It protects nothing: the data is protected server-side — the
filter chain, the tenant filter (L1) and `@TenantId` — and would stay protected if that file were
deleted. A route guard is bypassed by opening the developer tools; that is exactly why security is
not there.

**The SPA and the API must be same-origin.** Angular attaches the `X-XSRF-TOKEN` header only on an
identical origin — otherwise it attaches nothing at all and **every `POST` answers `403`** with no
usable message. In development, `proxy.conf.json` guarantees it: the browser only ever talks to
`localhost:4200`. Changing that proxy, or calling the gateway directly from the browser, breaks
sign-in in a way that looks like anything but a proxy problem.

## Scripts

| Script | Purpose |
|--------|---------|
| `yarn start` | Dev server at http://localhost:4200 |
| `yarn build` | Production build to `dist/` |
| `yarn test` | Run unit tests |
| `yarn watch` | Build in watch mode (dev) |

## Troubleshooting

### Yarn issues
If Yarn commands fail, reinstall dependencies:
```bash
rm -rf node_modules
yarn install
```

### Node version mismatch
Angular 22 requires Node `^22.22.3 || ^24.15.0 || >=26.0.0`. If you use nvm:
```bash
nvm use 22
```

### Port 4200 already in use
Run the dev server on a different port:
```bash
ng serve --port 4300
```
