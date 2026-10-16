_None AI text_

# Emporia
A market place system for trading commodities with fokus on simplicity and pragmatism.

## Background
This is just another AI slope project. The aim is to build a simple and pragmatic trading system 
target low end /startup markets minimizing the implementation and deployment of a trading venue.

Personally just want to explore Claude capabilities. I have +30 years in the 
software industri, and I am a bit curious about what AI can bring to the table when it comes to 
productivity and quality.
- In general, I was most impressed what AI can do. What has impressed me are
  - Understand and work on fairly large scope of functionality. When refactoring it keep track on all dependencies and side effects.
  - Having the ability to add tests of functionality added. However, I can say how good this testa are, I guess you will not know until the code is combat proven.
  - Having the ability to understand and apply best practise for industri technology, in this case trading technology.
  
The takeaway from this project is;
- That I am  fairly ignorant in driving the AI agent (Claude). Writing the right prompt to achieve the best possible result should not be taken for granted.
- The efficiency is flabbergasting. There does not seam to be any gaps in technology knowledge. The speed with what code could be developed. If I had takin this on by myself it had taking many weeks maybe even months. Now it was done in four days, and it was not four full days :-)
- I can not see how AI will not be part of SW projects in the future. The question is how it will be used? It will for sure affect the SW industry quite a bit. *Glad I am retired*

## The system

Emporia is a marketplace for commodity trading, built as **one server process**: a Java 21 / Javalin web
server with the matching engine, bulletin board, REST API, WebSocket feed and web pages all in the same
process. The database is MongoDB or, for the smallest setup, SQLite: a single file, nothing to install
(chosen in the configuration). There is no message bus or cluster to run, so the whole system is one
application and one database. It runs stand-alone as a fat jar, or as a WAR in Tomcat 10.1+. A Linux
installer (`./gradlew :Server:installer` makes `emporia-<version>.run`) installs the fat jar as a systemd
service, with SQLite as its database; it asks for the directory, the HTTPS port and the certificate.

### Trading
- **Continuous order books**: limit orders matched immediately in price-time priority. **Auction**
  markets collect orders and uncross them at one clearing price at scheduled times. A pre-open phase
  ends with an opening uncross.
- **Bulletin markets**: for less standardized, bilateral business (OTC). A firm posts a bulletin
  (instrument as free text; side, price and quantity optional). Another firm makes an offer on it, and
  the owner accepts or rejects it. Nothing is matched automatically.
- **Client accounts** on orders, bulletins and offers, with positions kept per instrument and account.
- **Market hours**: markets open and close by themselves on their schedule (pre-open, open, closed);
  an operator can also suspend a market.
- **Chat** between the trading users of all firms.

### Interfaces
- **Web trading interface** (HTTP/HTTPS, any browser, nothing to install): live prices, order book, order
  entry, own orders and trades, portfolio, bulletins and offers, chat. Popups and desktop notifications
  for trades and offers.
- **REST API** (JSON over HTTP, session login) for trading applications: everything the web page can do.
- **WebSocket feed** pushing prices, market states and the firm's own orders, trades and offers in real time.
- **Admin web interface** for firms, users, authorizations, markets, instruments and bulletin markets,
  described with screenshots in the [admin guide](https://hoddmimes.com/emporia/admin/admin-guide.html).

### Performance and durability
- Every trade is written to the database with a **durable write before anyone is told about it**
  (journaled in MongoDB, fsynced in SQLite), so no trade is lost if the host fails. Writes are grouped:
  one disk flush (about **2 ms** with MongoDB on the demo server) stores all the trades made meanwhile.
- Each order book has its own lock, so orders in different instruments are matched in parallel.
- Measured with the included load test client: **several thousand orders per second** 
  on one server.

### Database
The server stores its data in **MongoDB or SQLite**, chosen by `database` in the configuration file
(`emporia.json`); both give the same functionality.
- **MongoDB** (the default): `"database": {"type": "mongodb", "host": "127.0.0.1", "port": 27017, "name": "emporia"}`.
  The database and its collections are created when first used.
- **SQLite**: `"database": {"type": "sqlite", "file": "./emporia.db"}`. Nothing to install: the database
  is a single file, created with its tables at the first start (its directory must exist and be writable).
  Suits a small market or a test setup.

There is no tool to move data between the two.

### Documentation
- [User guide](https://hoddmimes.com/emporia/user-guide.html): the web trading interface, with screenshots.
- [Trading API](https://hoddmimes.com/emporia/api-doc.html): REST endpoints and WebSocket messages for developers.
- [Admin guide](https://hoddmimes.com/emporia/admin/admin-guide.html): the administration interface (firms,
  users, authorizations, markets, bulletin markets, instruments), with screenshots.
- The pages are part of the web application (`Server/WebContent/user-guide.html`, `api-doc.html` and
  `admin/admin-guide.html`) and are served by every running server, e.g. at `/emporia/user-guide.html`;
  the links above go to the demo server. The trading page links to the user guide under Help, the admin
  pages to the admin guide in their menu.

### Also in the project
- `TestClient`: a load and test client driven by JSON scripts, using only the REST API and WebSocket.
- `Simulator`: generates continuous order flow and bulletins to make a market look alive.