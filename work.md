# Work
This document outline what is being worked on and at the same time shows a timeline of the work being done.

* 26-10-09 Established project and created a framework running the server. Add a claude init/principle document.
* 26-10-09 Created the Server app: Javalin web server, configuration, trading-user and admin login/authorization and the user database layer, ported from EmporiaX.
* 26-10-09 Added Server/WebContent with the trading client UI and the admin UI (under /admin) from EmporiaX; trading session now carries authorized markets.
* 26-10-09 Ported the complete EmporiaX admin functionality to the Server: firms, users, markets, instruments (incl. bulk load) and authorizations.
* 26-10-10 Added the trading endpoints /api/markets and /api/instruments, limited to the markets the user's firm is authorized for, so the trading market view loads markets and instruments.
* 26-10-10 Added InstrumentCache (from EmporiaX, without IPMC): markets and instruments are loaded at startup, kept in sync by the admin endpoints and used for trading reads, authorization checks and id allocation.
* 26-10-10 Deleting a market that still has instruments is now refused (409), so a reused marketId can't inherit orphaned instruments.
* 26-10-10 Added the /ws WebSocket for price updates (PriceFeed): session-authenticated, ping/pong, updates filtered by the firm's authorized markets. No price source exists yet.
* 26-10-10 Migrated the EmporiaX matching engine (continuous and auction books, auction scheduler) into the server's me package, with REST order entry (/api/orders, /api/trades), book changes published on the /ws price feed and the first unit tests.
* 26-10-10 Added the MarketStateScheduler: market states follow the market hours (PREOPEN/OPEN/CLOSED) on weekdays and stay CLOSED on weekends; OPEN markets are closed at startup, while -mode test opens all non-suspended markets and disables the scheduler. Also fixed admin edits wiping market and instrument names.
* 26-10-10 Added the TestClient subproject: a REST test program that runs concurrent trading sessions with AddOrder/DeleteOrder directives from a JSON file, repeated at a given txRate.
* 26-10-10 Fixed orders being rejected in OPEN markets: the engine required instruments to be ACTIVE, but instruments bulk loaded from EmporiaX files are stored as OPEN. Now only SUSPENDED instruments block orders, and the bulk load stores statuses other than SUSPENDED as ACTIVE.
