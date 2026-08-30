# NEXT_DEFAULTS — moved

The reasoning, the rejected alternatives and every bug are now in
[`DEVELOPMENT.md`](DEVELOPMENT.md), split away from [`HANDOFF.md`](HANDOFF.md) so that the handoff is
the finished state and nothing else.

**This stub is deliberate.** `MANTRA_MANIFEST` tells a new session to read `NEXT_DEFAULTS.md` by
name, and a session that follows that instruction and finds nothing would rebuild decisions that
have already been paid for twice. It costs five lines to make that impossible.
