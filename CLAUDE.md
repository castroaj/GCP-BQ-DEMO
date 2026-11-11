## Agent skills

### Issue tracker

Issues live in GitHub Issues (castroaj/gcp-cloud-run-big-query-template) via the `gh` CLI. See `docs/agents/issue-tracker.md`.

Every issue added to a Wayfinder map must be assigned that map's milestone (`gh issue create --milestone "<map milestone title>"`, or `gh issue edit <n> --milestone "<title>"`). The map issue's own milestone gives the title, e.g. `Telecom Calls BigQuery + Cloud Run prototype (map #1)`. Check after creating that the milestone is set.

### Triage labels

Default five-label vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.
