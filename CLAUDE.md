# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Emporia is a marketplace system for trading commodities. Its focus is simplicity and pragmatism, so prefer straightforward solutions over elaborate abstractions.

The project is at an early stage. It is a multi-project Gradle build (group `com.hoddmimes`). The root project holds no code; it only aggregates the subprojects declared in `settings.gradle` (currently `Server`), which use the standard `src/main`/`src/test` layout.

## Server architecture

`Server` (package `com.hoddmimes.emporia.server`) is a single-process marketplace server: a Javalin 6 web server backed by MongoDB. It was ported from EmporiaX: the Javalin setup, the login/authorization mechanisms, the complete admin interface and its database layer. EmporiaX's trading endpoints, matching engine, instrument cache and IPMC/TCP messaging were not ported.

- `Server` (main class) takes `-config <file>`, reads the JSON config (`Server/emporia.json`), connects `Database` and starts `RestApi`. The config holds `sessionInactivityTime` (`mm:ss`), `database` (host/port/name) and `http_interface` (`root` web dir, `interface`, `http_port` and/or `https_port` + `ssl.cert`/`ssl.key` PEM paths, served via the Javalin SSL plugin).
- `RestApi` builds the Javalin app and registers each class in `endpoints/` implementing `RestEndpoint` (one class per route for login/session, one `Admin*Endpoints` class per admin resource); shared dependencies reach endpoints through `RestApiContext`. New endpoints are added to the list in `RestApi.declareEndpoints`.
- Authentication is HTTP-session based. A `before("/api/*")` filter requires the session attribute `admin-user` for `/api/admin/*` and `user` for every other `/api/*` path, except the login/session paths listed in `RestApi.PUBLIC_PATHS`. Trading users log in at `/api/login` with firm, userId and password; admins at `/api/admin/login`. One browser session can hold both logins, so each logout removes only its own attributes (`RestApiContext.logout`).
- `Server/WebContent` is the static web root, ported from EmporiaX. The trading client UI sits at the root (`index.html`, `login.html`, `market-view.html`, `css/tr.css`, `js/tr-session.js`). The admin UI is under `admin/` (`/admin` redirects to `/admin/login.html`), with every page and admin API call prefixed `/admin` and `/api/admin` so it doesn't clash with the trading UI. All admin pages are backed by endpoints. The trading `market-view.html` calls `/api/markets` and `/api/instruments?marketId=N` (`MarketEndpoints`, `InstrumentEndpoints`), which return only markets the user's firm is authorized for (403 for an unauthorized market). Authorizations are read from MongoDB on each request (`RestApiContext.authorizedMarkets`), not from the login-time `markets` session attribute, so admin changes apply at once. The page's `/ws` WebSocket (`PriceFeedEndpoint`) pushes `PriceUpdate` messages; the upgrade is rejected (401) unless the HTTP session has a trading user, the client may send `ping` (answered `pong`) and other client messages are ignored. Price sources call `RestApi.priceFeed().publish(PriceUpdate)`; `PriceFeed` sends each update only to clients whose firm is authorized for the instrument's market, resolved once when the client connects. Nothing produces prices yet, since there is no matching engine.
- `Database` wraps MongoDB collections `Firm`, `User`, `Markets`, `Instruments`, `Authorization` (firm → market, `*` = all markets) and `Admin`; MongoDB is the master store. A new market's `marketId` is the highest existing one + 1, so a market can only be deleted once it has no instruments (otherwise a new market could reuse its id and inherit them). An `instrumentId` is a long with the `marketId` in the high 32 bits and a per-market sequence (from 1) in the low 32 bits; it is stored as a string. Passwords are stored as SHA-512 hex. A default admin `Admin`/`changeit` is created when the `Admin` collection is empty.
- `InstrumentCache` holds all markets and instruments in memory (ported from EmporiaX, without IPMC). `Server` loads it from MongoDB at startup and it is reached through `RestApiContext.instrumentCache`. Read markets and instruments from the cache wherever possible: the trading endpoints, market-existence checks, the `*` authorization expansion and new `marketId`/`instrumentId` allocation all use it. Only the admin list/get endpoints read MongoDB, because the admin pages need each document's `mongoId`. Every market/instrument write goes to MongoDB first; the endpoint then re-reads the document and calls `putMarket`/`putInstrument` (or `removeMarket`/`removeInstrument`), which notify `MarketCallbacks`/`InstrumentCallbacks` subscribers.

Run it with `./gradlew :Server:run`, which uses `Server/` as working directory and `-config emporia.json`; MongoDB must be running on the configured host. Logging is Log4j2 (`src/main/resources/log4j2.xml`), writing to the console and `server.log`.

## Build and test

Use the Gradle wrapper (Gradle 9.2.1):

```bash
./gradlew build                                     # compile + test
./gradlew test                                      # all tests (JUnit 5 / Jupiter)
./gradlew test --tests 'com.hoddmimes.SomeTest'     # single test class
./gradlew test --tests 'com.hoddmimes.SomeTest.method'  # single test method
./gradlew :Server:test --tests 'com.hoddmimes.SomeTest' # test in the Server subproject only
```

Scope `--tests` to a subproject (`:Server:test`) once more than one project has tests; otherwise Gradle fails in the projects where the filter matches nothing.

Settings shared by all subprojects (Java plugin, group/version, repositories, JUnit 5) live in the `subprojects {}` block of the root `build.gradle`; a subproject's own `build.gradle` holds only what is specific to it, such as its dependencies. The shared settings include a Java 21 toolchain, matching the IntelliJ project setting, so the build does not depend on the JDK on the path (the machine default is JDK 25).

## Migrating code from EmporiaX

`../EmporiaX` is an earlier, similar project. Parts of its code will be migrated into Emporia over time.

- **`../EmporiaX` is read-only.** Never create, modify, delete or commit anything there, and never run commands that write to it, such as builds that produce output in its tree.
- **Migrate only on explicit instruction.** Copy or port only the code the user names. Don't bring over EmporiaX code proactively, even when it looks relevant.
- Reading EmporiaX to understand it or answer questions is fine.

## Work log

`work.md` is a dated timeline of the work done on the project (entries look like `* YY-MM-DD description`). When a significant piece of work is completed, add an entry in that format.
