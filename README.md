_None AI text_

# Emporia
A market place system for trading commodities with fokus on simplicity and pragmatism.

## Background
This is just another AI slope project. The aim with this project was to prove that it is possible for a retired 
program to build a marketplace for smaller startup marketplaces with basic needs with AI in less than week.

Here it is 2026-10-15 :-) My opinion on these kinds of AI slope projects are that the SW itself has no value.

The value lies in.
- *Trust*, who is behind the project, who is supporting the project. What is the aim and intention.
- *Combat Proven*, even if compiling, including hundreds of AI test. Nothing is production quality until the takers had pressure-tested the solution. 

_AI Generate text_


## The system

Emporia is a marketplace for commodity trading, built as **one server process**: a Java 21 / Javalin web
server with the matching engine, bulletin board, REST API, WebSocket feed and web pages all in the same
process. MongoDB is the database. There is no message bus or cluster to run, so the whole system is one
application and one database. It runs stand-alone as a fat jar, or as a WAR in Tomcat 10.1+.

### Trading
- **Continuous order books**: limit orders matched immediately in price-time priority. **Auction**
  markets collect orders and uncross them at one clearing price at scheduled times. A pre-open phase
  ends with an opening uncross.
- **Bulletin markets**: for less standardised, bilateral business (OTC). A firm posts a bulletin
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
- **Admin web interface** for firms, users, authorizations, markets, instruments and bulletin markets.

### Performance and durability
- Every trade is written to MongoDB with a **journaled write before anyone is told about it**, so no
  trade is lost if the host fails. Writes are grouped: one disk flush (about **2 ms**) stores all the
  trades made meanwhile.
- Each order book has its own lock, so orders in different instruments are matched in parallel.
- Measured with the included load test client: **several thousand orders per second** 
  on one server.

### Documentation
- [User guide](https://hoddmimes.com/emporia/user-guide.html): the web trading interface, with screenshots.
- [Trading API](https://hoddmimes.com/emporia/api-doc.html): REST endpoints and WebSocket messages for developers.
- Both pages are part of the web application (`Server/WebContent/user-guide.html` and `api-doc.html`) and
  are served by every running server at `/emporia/user-guide.html` and `/emporia/api-doc.html`; the
  links above go to the demo server. The trading page links to the guide under Help.

### Also in the project
- `TestClient`: a load and test client driven by JSON scripts, using only the REST API and WebSocket.
- `Simulator`: generates continuous order flow and bulletins to make a market look alive.