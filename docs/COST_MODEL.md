# COST_MODEL — template (not filled in)

Status: template, 23 Oct 2026. SPEC §13.2 asks for this in Phase 0. It only matters for v1 if first-party storage (Phase 8) is on the table: with bring-your-own Google Drive the hosting cost to us is zero and the cost sits on the user's own Drive quota.

**Do not fill the price cells from memory.** Copy each price from the provider's current price page and record the URL and date next to it.

## Inputs

| Symbol | Meaning | Value | Source / date |
| --- | --- | --- | --- |
| L | Average library size per user (GB) | | |
| g | Library growth per user per month (GB) | | |
| d | Dedupe/compression ratio (stored ÷ logical, ≤ 1) | | |
| r | Share of users who restore in a given month | | |
| n | Number of users | 10 / 100 / 1000 | |
| p_store | Provider storage price (per GB-month) | | |
| p_egress | Provider egress price (per GB) | | |
| p_req | Provider request price (per 1,000 requests) | | |
| o | Objects per GB after small-file bundling | | spike 1/4 |
| k | Requests per object per upload + verification pass | | |

## Formulas (month *m*)

```
stored_GB(m)   = d × (L + g × m)                       per user
storage_cost   = n × stored_GB(m) × p_store
egress_cost    = n × r × (L × d) × p_egress             restore traffic
request_cost   = n × (g × d × o × k / 1000) × p_req     steady-state uploads and verification
total(m)       = storage_cost + egress_cost + request_cost
per_user(m)    = total(m) / n
```

Verification downloads (SPEC §8.6 levels 2–3) count as egress; add them once the sampling rate is chosen.

## Output table

| Provider option | n = 10 | n = 100 | n = 1000 | Notes |
| --- | --- | --- | --- | --- |
| BYO Google Drive | 0 to us | 0 to us | 0 to us | User's own quota |
| S3-compatible (first-party) | | | | |
| Other | | | | |

## Open

- Which provider options to price (decide with ADR-004 and open question 3 in `DECISIONS.md`).
- Whether payment processing, support and compliance costs belong here or in a separate business model.
