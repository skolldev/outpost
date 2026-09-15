# Attribute equality is textual, served by typed containment

An [[Attribute]] filter on [[Log Record]]s — `attr=user.id=42` on `GET /logs`, the timeline, the live tail, and the MCP `search_logs` Tool — matches when the attribute's value *reads as* the filter text: `42` matches the number 42 and the string `"42"`, `true` matches the boolean and the string. Attribute values keep the JSON type the SDK sent, while a filter is text a person typed or clicked, so something has to reconcile the two. We keep the textual meaning the filter always had, but no longer implement it as text extraction (`attributes->>key = value`), which no index can serve (#132). Instead the server expands the text into every JSON value it could denote and ORs one containment per candidate — `attributes @> '{"user.id":42}' OR attributes @> '{"user.id":"42"}'` — each of which the existing `jsonb_ops` GIN index answers. The live tail applies the same expansion in-process, so a filter means the same thing on a record streamed now and on one read back later.

## Considered options

- **Text extraction (`->>`), as before.** The semantics people expect, and unindexable: a filter matching one Log Record in a thousand cost 11x the unfiltered page (#132). Putting attribute filters in the UI would have made that the Logs page's most common slow path.
- **Typed filters.** The chip, or the wire format, carries a type and the server binds a single containment. As fast as the chosen option and simpler, but a filter typed as `42` against an SDK that sends the id as a string matches nothing, with nothing on screen saying why — and the type is not something a person filtering logs knows or should need to.
- **Typed containment of the text as a string only** (the fix first proposed on #132). Fast, but silently changes the meaning for every numeric and boolean attribute, and diverges from the live tail.

## Consequences

- **Numeric candidates compare as numbers**, so `3` also matches `3.0`. Text extraction did not; containment does, and so does the tail.
- **Only scalar values can match.** An attribute holding an object, an array or JSON `null` matches no equality filter, where text extraction would have matched an object's serialized JSON. The Logs page offers no click-to-filter on such values.
- **The expansion is an interface, not an implementation detail.** MCP callers and saved links depend on it. Adding a candidate form (say, a date) changes what existing filters match.
- **A common value is not made cheaper.** Containment helps the selective filter; one matching half the window can still cost a read of the window. That shape is measured and recorded in `docs/performance/measuring-retrieval.md` rather than guarded.
