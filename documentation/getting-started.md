# Getting Started

## Prerequisites

- Java 21
- Node.js >= 20
- Docker & Docker Compose

## Running the application

All commands below should be run from the **project root** directory.

### 1. Configure environment

Copy `.env.properties.example` to `.env.properties` and fill in a GitHub token and a
repository to work in.

**There is no mock.** GitHub's API is large enough that a fake of it would be a second thing
to keep true, and it would be wrong in exactly the places that matter — pagination, the
`Link` header, the difference between a check run and a commit status. The sandbox talks to
real GitHub, so point it at a throwaway repository of your own and give it a token scoped to
that repository and nothing else.

### 2. Start Docker dependencies

Make sure Docker is running, then start the required services:

```shell
./gradlew :backend:app:composeUp
```

### 3. Start the backend

```shell
./gradlew :backend:app:bootRun
```

### 4. Start the frontend

```shell
cd frontend
npm install
npm run libs-build-all
npm start
```

## Seeing it work

One case is autodeployed. **Example** has two service tasks: the first reads issue 1 of the
repository in `GITHUB_REPOSITORY` into a process variable called `issue`, and the second
comments on it. Start it from the UI and the comment appears on GitHub.

The plugin configuration is autodeployed from
`backend/app/src/main/resources/config/plugin/github.pluginconfig.json`, which reads its token
and repository out of the environment rather than holding them.

### Checking a configuration before building on it

`get-authenticated-user` takes no properties beyond a result variable and reports which
account the token belongs to. It is the cheapest way to find out whether a configuration
works, and which identity a pull request opened through it will appear to come from.

### Keycloak users

The application has a few test users that are preconfigured.

| Name         | Role           | Username  | Password  |
|--------------|----------------|-----------|-----------|
| James Vance  | ROLE_USER      | user      | user      |
| Asha Miller  | ROLE_ADMIN     | admin     | admin     |
| Morgan Finch | ROLE_DEVELOPER | developer | developer |

## Plugin development

The plugin source code is located in:

- Backend: `backend/plugin/src/`
- Frontend: `frontend/projects/plugin/src/`

### Adding an action

Four places, in this order:

1. `GitHubOperations` — the call itself, and the projection of what comes back.
2. `GitHubPlugin` — the `@PluginAction`, which is a declaration plus a `store(...)` and
   nothing else.
3. `frontend/projects/plugin/src/lib/models/config.ts` — the interface, mirroring the action
   properties.
4. A component under `frontend/projects/plugin/src/lib/components/<action-key>/`, extending
   `GitHubFunctionConfigurationComponent`, plus its entry in the module, the specification
   and `public_api.ts`.

The key in the `@PluginAction` and the key in `functionConfigurationComponents` have to match
exactly, or the admin UI offers the action with no configuration screen behind it.

The existing components were generated from one spec table, which is why they all look alike.
They are ordinary files now, and editing one by hand is fine.

### Tests

```shell
./gradlew :backend:plugin:test          # unit tests
./gradlew :backend:plugin:ktlintCheck   # formatting, enforced in CI
cd frontend && npx ng build @valtimo-plugins/github
```

The Angular build is the frontend's real check: it compiles every template ahead of time, so
a binding to a property that does not exist fails the build rather than the admin UI.

For more information on how to build a plugin, see
the [Custom Plugin Definition](https://docs.valtimo.nl/features/plugins/plugins/custom-plugin-definition) documentation.
