# Demo chapter

`DuesDesk-demo-50-member-chapter.json` is a simulated chapter you can load into DuesDesk to explore the app with realistic data. Everything in it is invented.

## What's in it

- **People:** 50 current members and 16 graduated members (in the archive), across five terms, Fall 2024 to Fall 2026.
- **Dues:** about 330 payments, with a mix of on-time payers, late payers, split payers and payment plans, plus a few fines. Most payments came in through Venmo, bank transfers and GreekBill and were matched to members, some under a parent's name.
- **This semester:** 28 paid in full, 9 on payment plans, 13 past due (two of those are manually vouched).
- **Cash flow:** about 700 transactions (about $484k in and $346k out): dues, house rent, per-capita fees, events, fundraising, sponsorships, utilities, insurance and more. 5 deposits are waiting to be matched on the reconciliation screen.
- **Also included:** recurring bills with their payment history, house residents and rent, six chair budgets with four closed terms of history, 79 reminder texts, and weekly trend data for the dashboard charts.

Dates are fixed, running up to **25 Oct 2026**. If you load it later, newer dates will simply show as overdue.

## How to load it

1. Open DuesDesk and go to **Import & Export**.
2. In the **Import saved data** section, choose `DuesDesk-demo-50-member-chapter.json` as the **Backup file**. Leave the password blank.
3. Click **Import & replace all data**.

**Importing replaces everything in the app with this file.** Use a fresh install, or export your own data first.

## Regenerating

`node generate-demo-chapter.mjs [output-file]` rebuilds the file. It is deterministic, so you get the same data every time, and it checks the numbers add up before writing.
