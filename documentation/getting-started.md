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

## Seeing it work: the fixtures

One case type is autodeployed, **GitHub**, holding eight fixture processes. Between them
they exercise all 33 actions against a real repository. Start one from the case list, fill
in the repository on its start form, and step through: every action is followed by a task
showing exactly what it stored, so you can read the answer before deciding whether the next
step is what you wanted.

| Fixture                      | Exercises                                                                                                          |
|------------------------------|--------------------------------------------------------------------------------------------------------------------|
| **repository**               | `get-authenticated-user`, `get-repository`, `list-repositories`                                                     |
| **issues**                   | `create-label`, `create-issue`, `comment`, `get-issue`, `list-issues`, `search-issues`, `update-issue`              |
| **bestanden en branches**    | `create-branch`, `create-or-update-file`, `get-file-content` (file and directory), `rest-request`                   |
| **pull requests**            | the lifecycle, ending in `merge-pull-request` or a close                                                            |
| **reviews**                  | `get-pull-request`, `list-review-threads`, `reply-to-review-comment`, `resolve-review-thread`, `review-pull-request`, `request-reviewers` |
| **checks en workflows**      | `list-workflow-runs`, `get-workflow-run`, `get-job-logs`, `get-check-status`, `rerun-workflow`                      |
| **projectborden**            | `get-project-items`, `set-project-item-field`                                                                       |
| **REST en GraphQL**          | `rest-request`, `graphql-query`                                                                                     |

**Start with the repository fixture.** It writes nothing at all, so it is the quickest way
to find out whether a configuration works and which identity it acts as — which is also what
decides whether `review-pull-request` will be allowed to approve anything, since GitHub does
not let an account approve its own pull request.

### What the fixtures write, and what they clean up

These run against real GitHub. Point them at a throwaway repository.

The fixtures that create things clean up after themselves where GitHub allows it: branches
are deleted again (through `rest-request`, since there is no delete-branch action), pull
requests are closed, and the fixture issue is closed — GitHub has no delete for an issue, so
closed is the tidiest end state available.

**Every step that cannot be undone is behind a checkbox, off by default:**

- merging a pull request, which writes to the default branch — off means close instead
- answering and resolving a review thread, which writes into somebody else's review
- requesting reviewers, which notifies people
- re-running a workflow, which costs a CI build
- writing a project field

### Fixtures that need something to exist first

Three of them read before they write, and have nothing to read in an empty repository:

- **reviews** wants a pull request number that already has inline review comments on it.
  Run the pull request fixture first and feed its number in, or point it at a real one.
- **checks** needs a repository with GitHub Actions history — with no runs, the first step
  returns an empty list and the steps after it have no run to open.
- **projectborden** needs an issue that sits on a project board. Writing a field back needs
  three node ids that only the read gives you, so the usual way to use it is twice: once to
  read the ids out, then again with them filled in and the write switched on.

Each run computes its own `runId` from the clock, so branch and file names are unique and
running a fixture twice does not collide with itself.

The plugin configuration is autodeployed from
`backend/app/src/main/resources/config/plugin/github.pluginconfig.json`, which reads its token
and repository out of the environment rather than holding them.

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
cd frontend && npx ng build @valtimo-plugins/github-plugin
```

The Angular build is the frontend's real check: it compiles every template ahead of time, so
a binding to a property that does not exist fails the build rather than the admin UI.

For more information on how to build a plugin, see
the [Custom Plugin Definition](https://docs.valtimo.nl/features/plugins/plugins/custom-plugin-definition) documentation.
