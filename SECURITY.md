# Security Policy

## Reporting a Vulnerability

Report vulnerabilities privately via
[GitHub security advisories for this repository](https://github.com/hechtcarmel/jetbrains-debugger-mcp-plugin/security/advisories/new).
Please do not open a public issue for a security problem. Reports get a response within a week;
fixes ship in the next plugin release.

Only the latest released version is supported with security fixes.

## Threat Model — read this before exposing the server

This plugin embeds an **unauthenticated HTTP server** in your IDE. There are no tokens, no
credentials, and no authorization checks on any of its three transports. Understand exactly what
that means before changing any network setting:

- **Localhost by default, but user-configurable to `0.0.0.0`.** The server binds `127.0.0.1`
  unless you change the bind address in <kbd>Settings</kbd> > <kbd>Tools</kbd> >
  <kbd>Debugger MCP Server</kbd>. Binding a non-loopback address exposes the full tool surface to
  the network **with no authentication whatsoever**.

- **The tool surface is arbitrary code execution in the debuggee.** `evaluate_expression` and
  `set_variable` run code inside whatever process you are debugging. `set_breakpoint` conditions
  and log messages are likewise evaluated by the debugger. Anyone who can complete an HTTP request
  to the port can execute code in your debugged process — and through it, act with your user
  account's privileges.

- **The Origin guard is the only browser-facing control, and that is all it is.** Requests whose
  `Origin` header is not a loopback origin are rejected; this stops a malicious web page in your
  browser from driving your debugger. A request with **no** `Origin` header is allowed **by
  design** — `curl` and most MCP clients send none, and rejecting them would break every
  legitimate client. The guard therefore provides no protection at all against non-browser
  clients that can reach the port. On the default loopback binding that means: **any local
  process can drive your debugger.**

- **The evaluate-expression safety modes are best-effort filters, not a sandbox.** The default
  mode is **Unrestricted** (no filtering). `Default blocklist` and `Read-only` exist, but the
  expression scanner has known, documented bypasses: interpolated string templates are blanked
  before scanning, an unbalanced quote blanks everything after it, only the first 10,000
  characters are scanned, and the blocklist targets JVM APIs only (e.g. Python's `os.system` or
  Node's `child_process` pass unblocked). The only configuration with a soundness argument is
  **Read-only mode on Java code**, where the PSI-based analyzer can actually prove the absence of
  side effects. Treat every other combination as advisory. Breakpoint conditions and log
  messages do **not** pass through the guard at all.

## Practical Guidance

- Keep the default `127.0.0.1` binding. Never bind `0.0.0.0` on a machine reachable from an
  untrusted network.
- If you need remote access, put an authenticating reverse proxy or an SSH tunnel in front of the
  port — the plugin will not authenticate for you.
- Assume anything that can send HTTP to the port can execute code as you. Scope what you debug
  accordingly.
- If your threat model includes the AI agent itself running hostile expressions, set the safety
  mode to Read-only, debug Java, and still review what the agent evaluates.
