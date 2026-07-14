---
layout: post
title: "Pointing OWASP ZAP at my Spring Boot API — from a one-off scan to an authenticated one"
date: 2026-07-02
categories: tech
---

The [HTTP-vs-HTTPS post]({% post_url tech/2026-06-29-watching-http-vs-https-between-laptop-and-phone %}) was about *watching* traffic. This one is about *attacking* it — but against my own code, on `localhost`, which is the only place this kind of thing is legal to do. I wanted to point [OWASP ZAP](https://www.zaproxy.org/) at a Spring Boot API and have it actually find something.

The journey had two stages, and the gap between them is the whole lesson:

1. **The one-off scan** — point ZAP at a single URL, hit attack, read the alerts. Works in five minutes, but only ever tests the one endpoint you handed it.
2. **The real scan** — hand ZAP the OpenAPI spec so it discovers *every* endpoint, then teach it to log in so it can reach the endpoints that sit behind auth.

Most APIs worth testing are the second kind. So the second stage is the point; the first is just how you get your bearings.

---

## 1. Let Spring Boot describe itself: springdoc

ZAP is far more useful when it knows the shape of the API instead of guessing URLs. Spring Boot won't publish that shape on its own — you add [springdoc-openapi](https://springdoc.org/), which reads your controllers and generates an OpenAPI document plus a Swagger UI.

One dependency:

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.6.0</version>
</dependency>
```

Restart the app and you get two things for free:

| URL | What it is |
|-----|------------|
| `/swagger-ui.html` | Human-readable UI to poke endpoints by hand |
| `/v3/api-docs` | The machine-readable OpenAPI JSON — **this is what ZAP eats** |

> **Watch the context path.** If your app runs with `server.servlet.context-path=/api`, the docs move to `http://localhost:8081/api/v3/api-docs`. That's the URL I use below — check yours, because a wrong path here means ZAP imports nothing.

The `api-docs` JSON is the difference between ZAP knowing about one endpoint and ZAP knowing about all of them. That single file is the whole reason the automated stage works.

---

## 2. Stage one — the five-minute scan

Before wiring anything up, I wanted to confirm ZAP could find a real bug at all. So I used the crudest possible path.

**ZAP → Quick Start → Automated Scan.**

Paste one live URL — a search endpoint that takes user input, because input is where injection bugs live:

```
http://localhost:8081/api/user/search?name=Alice
```

Hit **Attack**. ZAP spiders from that URL, then runs its active scan — throwing SQLi payloads, XSS strings, and malformed input at every parameter it found. When it finishes, the **Alerts** tab fills in, colour-coded by risk:

- 🔴 **High** — the ones that matter (SQL injection, path traversal)
- 🟠 **Medium** — missing CSRF tokens, directory listing
- 🟡 **Low / ℹ️ Info** — missing security headers, verbose errors

This is genuinely satisfying the first time — you watch a tool find a SQLi hole you left in your own code. But it has a hard ceiling: it only tested what it could reach *from that one URL*. Anything the spider couldn't crawl to — and anything behind a login — is invisible. That's what stage two fixes.

---

## 3. Stage two — feed ZAP the whole API

Instead of one URL, hand ZAP the OpenAPI doc and let it enumerate every route.

**ZAP → Import → Import an OpenAPI Definition from a URL.**

```
http://localhost:8081/api/v3/api-docs
```

ZAP reads the spec and populates the **Sites** tree with every endpoint springdoc described — GETs, POSTs, path variables, query params, the lot. No spidering guesswork; it's working from the API's own map. This alone is a big jump in coverage over stage one.

But there's a wall coming. Most of those endpoints return `401 Unauthorized` the moment ZAP touches them, because a real API doesn't leave its data in the open. To test what's behind the login, ZAP has to *be* logged in. That's what a **Context** is for.

---

## 4. Teach ZAP to log in: the Context

A **Context** is ZAP's notion of "one application and how to authenticate to it." This is the piece my rough notes kept circling, so here it is in order.

### 4a. Create the context and give it a scope

Right-click a URL in the Sites tree → **Include in Context → New Context**. Name it `localhost:8081`.

Seed its scope with a known-good endpoint so ZAP knows which URLs belong to this app:

```
http://localhost:8081/api/user/search?name=Alice
```

Then widen the scope to everything on that host — in the context's **Include in Context** settings:

```
http://localhost:8081/api/.*
```

Anything matching that regex is now "in scope" and fair game for the attack. Anything outside it, ZAP leaves alone — which is exactly the discipline you want so you never accidentally scan a URL you don't own.

### 4b. Set JSON-based authentication

Open the context's **Authentication** panel and choose **JSON-based Authentication** — the right mode for a REST API that takes a JSON login body (not an HTML form).

**Login Request URL:**

```
http://localhost:8081/api/auth/login
```

**Login Request POST data** — this is the body ZAP sends, with placeholders it substitutes per user:

{% raw %}
```
{"email":"{%username%}","password":"{%password%}"}
```
{% endraw %}

{% raw %}Those `{%username%}` / `{%password%}` tokens are ZAP's own placeholders — it swaps in each user's real credentials at login time. Match the field names to what your `/auth/login` actually expects; mine keys on `email`, not `username`.{% endraw %}

### 4c. Tell ZAP when it's logged in

This is the part that quietly breaks authenticated scans. ZAP needs a **logged-in / logged-out indicator** — a signal that tells it whether a session is still valid. Without it, ZAP can't tell a live session from an expired one, re-authentication never fires, and half your "authenticated" scan silently runs logged out.

For a token API the usual signals are:

- **Logged-in indicator** — a regex matching something only present when authed (e.g. the `"token"` field in the login response, or a `200` on a `/me` endpoint).
- **Logged-out indicator** — a pattern that shows up on failure, like `HTTP/1.1 401` or `"error":"Unauthorized"`.

If your login returns a JWT you need on later requests, that's **Script-based** territory (a small ZAP script that lifts the token from the login response into an `Authorization: Bearer` header) — a step past plain JSON auth, but the same context otherwise.

### 4d. Add the user

In the context's **Users** panel, add one:

```
Name:     Gopal
Username: <the real login email>
Password: <the real password>
```

ZAP drops these into the `{% raw %}{%username%}{% endraw %}` / `{% raw %}{%password%}{% endraw %}` placeholders from 4b. You can add several users to test whether one can reach another's data — the cheap way to catch broken-object-level-authorization bugs.

### 4e. Session management

Set **Session Management** to match the app. Cookie-based sessions → **Cookie-Based**. JWT/bearer tokens → **HTTP Header** (or the script from 4c). Get this wrong and the token never rides along on the attack requests.

Click **OK** to save the context, then make sure the app is actually running before you launch anything.

---

## 5. Run the authenticated attack

Now it's aimed and credentialed:

1. In the **Sites** tree, select the portion of the API you want to hit (a single endpoint, or the whole `/api` subtree).
2. Right-click → **Attack → Active Scan**.
3. In the dialog, set **Context = `localhost:8081`** and **User = `Gopal`**.
4. Start it.

The difference from stage one is the whole point: ZAP authenticates as Gopal, keeps the session alive using the indicators from 4c, and attacks endpoints that returned `401` before. Coverage jumps from "the handful of public routes" to "everything Gopal can see." Read the results in the **Alerts** tab, same as before — just with a lot more surface actually tested.

---

## What I took away

The arc, start to finish:

1. **springdoc** — make Spring Boot publish its own OpenAPI map ✅
2. **Quick scan** — one URL, confirm ZAP finds real bugs ✅
3. **OpenAPI import** — trade one URL for every endpoint ✅
4. **Context + auth** — log in so the endpoints behind auth get tested ✅

The lesson that stuck: an unauthenticated scan tests the lobby; the authenticated scan tests the building. Getting there isn't about ZAP's attack payloads — those are automatic — it's about the *context* work: scope, login request, the logged-in indicator my notes almost forgot, and session management. Do that setup right and ZAP walks the whole API as a real user. And the whole thing only stays legal because the only target is my own code on `localhost` — the discipline of scoping to a host I own is the same one worth keeping every time.
